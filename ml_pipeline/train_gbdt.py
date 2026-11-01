#!/usr/bin/env python3
"""Train the reference GBDT sleep stager (LightGBM) and convert to ONNX.

Contract (must match ModelLoader.kt / GbdtReferenceEngine.kt):
  input  "features" float32 [N, 127]
  output "probs"    float32 [N, 4] class probabilities (logits:false)

Conversion: onnxmltools.convert_lightgbm with use_zipmap=False, input renamed
to "features" to match the on-device tensor name. Pinned pairing that is known
to work: lightgbm 4.x + onnxmltools >= 1.11 + onnx 1.14-1.17.

Validation: onnxruntime outputs vs LightGBM predict_proba on a holdout must
match within --tol (default 1e-4 absolute per class probability).

Usage:
  python ml_pipeline/train_gbdt.py --data ml_pipeline/data/shhs1 --out ml_pipeline/artifacts
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import features as F  # noqa: E402

import lightgbm as lgb  # noqa: E402
from sklearn.metrics import cohen_kappa_score, f1_score  # noqa: E402
from sklearn.model_selection import GroupShuffleSplit  # noqa: E402

STAGES = ["wake", "light", "deep", "rem"]
ENGINE_ID = "sploot-gbdt"


def load_epochs(data_dir: pathlib.Path):
    xs, ys, groups = [], [], []
    for shard in sorted(data_dir.glob("*.parquet")):
        df = pd.read_parquet(shard)
        if "label" not in df:
            continue
        df = df[df["label"] >= 0]
        if df.empty:
            continue
        xs.append(df[F.FEATURE_NAMES].to_numpy(dtype=np.float32))
        ys.append(df["label"].to_numpy(dtype=np.int64))
        groups.append(np.full(len(df), shard.stem))
    return np.concatenate(xs), np.concatenate(ys), np.concatenate(groups)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="ml_pipeline/data/shhs1")
    ap.add_argument("--out", default="ml_pipeline/artifacts")
    ap.add_argument("--tol", type=float, default=1e-4)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--num-leaves", type=int, default=63)
    ap.add_argument("--n-estimators", type=int, default=400)
    args = ap.parse_args()

    F.validate_feature_names()
    x, y, groups = load_epochs(pathlib.Path(args.data))
    print(f"{x.shape[0]} epochs, {len(np.unique(groups))} subjects")

    # NaN -> 0 (on-device PerNightNormalizer policy)
    x = np.nan_to_num(x, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)

    tr_idx, te_idx = next(GroupShuffleSplit(
        n_splits=1, test_size=0.2, random_state=args.seed).split(x, y, groups))

    counts = np.bincount(y[tr_idx], minlength=4)
    print("class counts:", counts.tolist())

    clf = lgb.LGBMClassifier(
        objective="multiclass", num_class=4, n_estimators=args.n_estimators,
        num_leaves=args.num_leaves, learning_rate=0.06, class_weight="balanced",
        random_state=args.seed, verbose=-1,
    )
    clf.fit(x[tr_idx], y[tr_idx])
    pred = clf.predict(x[te_idx])
    print(f"holdout accuracy {np.mean(pred == y[te_idx]):.4f}  "
          f"κ {cohen_kappa_score(y[te_idx], pred):.4f}  "
          f"macro-F1 {f1_score(y[te_idx], pred, average='macro'):.4f}")

    # ---------------- convert to ONNX ----------------
    from onnxmltools.convert import convert_lightgbm as convert_lgb
    from onnxmltools.convert.common.data_types import FloatTensorType
    import onnx

    onnx_model = convert_lgb(
        clf.booster_,
        name=ENGINE_ID,
        initial_types=[("features", FloatTensorType([None, len(F.FEATURE_NAMES)]))],
        zipmap=False,
    )
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    onnx_path = out / f"{ENGINE_ID}.onnx"
    onnx.save_model(onnx_model, str(onnx_path))

    # ---------------- validate vs LightGBM ----------------
    import onnxruntime as ort
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    out_name = sess.get_outputs()[-1].name
    probs_lgb = clf.predict_proba(x[te_idx])
    probs_ort = sess.run([out_name], {in_name: x[te_idx].astype(np.float32)})[0]
    err = np.abs(probs_ort - probs_lgb).max()
    print(f"max |onnx - lightgbm| prob delta on holdout: {err:.2e}")
    assert err <= args.tol, f"ONNX/LightGBM mismatch {err:.2e} > {args.tol}"

    # shape contract check
    single = sess.run([out_name], {in_name: x[te_idx][:1].astype(np.float32)})[0]
    assert single.shape == (1, 4), f"single-row output shape {single.shape} != (1,4)"

    meta = {
        "engineId": ENGINE_ID,
        "featureVersion": F.FEATURE_VERSION,
        "logits": False,
        "inputName": "features",
        "outputName": "probs",
        "inputShape": [1, 127],
        "outputClasses": STAGES,
        "placeholder": False,
        "onnxLightgbmMaxDelta": float(err),
    }
    (out / f"{ENGINE_ID}.json").write_text(json.dumps(meta, indent=2) + "\n")
    print(f"exported {onnx_path} + sidecar (validated within {args.tol})")


if __name__ == "__main__":
    main()
