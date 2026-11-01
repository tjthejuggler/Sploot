#!/usr/bin/env python3
"""Package trained artifacts into app/src/main/assets/models/ with contract gating.

Checks BEFORE anything is copied (a failing model NEVER overwrites the bundled
placeholders):
  sploot-seq : input float32 [1, T, 127] dynamic T -> output [T, 4],
               rows sum to 1 (softmax embedded, logits:false).
  sploot-gbdt: input float32 [N, 127] -> output [N, 4] probabilities.
  sidecars   : engineId match, featureVersion == manifest version
               (assets/features_manifest.json), logits false.
  hmm_transition.json (if present): 4x4 row-stochastic + length-4 initial.

Prints an upgrade report (old vs new bytes, placeholder -> trained).

Usage:
  python ml_pipeline/package_assets.py [--artifacts ml_pipeline/artifacts] [--dry-run]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import features as F  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
ASSETS = REPO / "app/src/main/assets/models"
MANIFEST = json.loads((REPO / "app/src/main/assets/features_manifest.json").read_text())

SEQ_ID, GBDT_ID = "sploot-seq", "sploot-gbdt"


def check_model(engine_id: str, onnx_path: pathlib.Path, meta: dict) -> list[str]:
    import onnxruntime as ort
    errs: list[str] = []

    if meta.get("engineId") != engine_id:
        errs.append(f"sidecar engineId {meta.get('engineId')!r} != {engine_id!r}")
    if meta.get("featureVersion") != MANIFEST["version"]:
        errs.append(f"featureVersion {meta.get('featureVersion')} != manifest {MANIFEST['version']}")
    if meta.get("logits", False):
        errs.append("sidecar declares logits:true — device applies no softmax")

    try:
        sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    except Exception as e:  # noqa: BLE001
        return errs + [f"session open failed: {e}"]

    inp = sess.get_inputs()[0]
    out = sess.get_outputs()[-1]
    rng = np.random.default_rng(0)
    x = rng.standard_normal((1, 60, 127)).astype(np.float32)
    if engine_id == GBDT_ID:
        x = x.reshape(60, 127)
    p = sess.run([out.name], {inp.name: x})[0]

    want_shape = (60, 4)
    if tuple(p.shape) != want_shape:
        errs.append(f"output shape {tuple(p.shape)} != {want_shape} for T/N=60")
    s = p.sum(axis=1)
    if not (np.all(s > 0.98) and np.all(s < 1.02)):
        errs.append(f"output rows not normalised (sums {s.min():.3f}..{s.max():.3f})")

    # dynamic T (seq only)
    if engine_id == SEQ_ID:
        x2 = rng.standard_normal((1, 137, 127)).astype(np.float32)
        p2 = sess.run([out.name], {inp.name: x2})[0]
        if tuple(p2.shape) != (137, 4):
            errs.append(f"T-dynamic shape check failed: {tuple(p2.shape)}")
    return errs


def check_hmm(path: pathlib.Path) -> list[str]:
    errs = []
    try:
        d = json.loads(path.read_text())
        t = np.array(d["transitions"], dtype=float)
        pi = np.array(d["initial"], dtype=float)
        if t.shape != (4, 4):
            errs.append(f"transitions shape {t.shape} != (4,4)")
        elif not np.allclose(t.sum(axis=1), 1.0, atol=1e-3):
            errs.append("transition rows not stochastic")
        if pi.shape != (4,):
            errs.append(f"initial shape {pi.shape} != (4,)")
        elif not np.isclose(pi.sum(), 1.0, atol=1e-3):
            errs.append("initial distribution not normalised")
    except Exception as e:  # noqa: BLE001
        errs.append(f"hmm json unreadable: {e}")
    return errs


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--artifacts", default="ml_pipeline/artifacts")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    art = pathlib.Path(args.artifacts)
    report: list[str] = []
    failures: list[str] = []

    for engine_id in (SEQ_ID, GBDT_ID):
        onnx_path = art / f"{engine_id}.onnx"
        json_path = art / f"{engine_id}.json"
        if not onnx_path.exists() or not json_path.exists():
            report.append(f"{engine_id}: no trained artifact in {art} — keeping bundled model")
            continue
        meta = json.loads(json_path.read_text())
        errs = check_model(engine_id, onnx_path, meta)
        if errs:
            failures += [f"{engine_id}: {e}" for e in errs]
            continue

        dest_onnx, dest_json = ASSETS / f"{engine_id}.onnx", ASSETS / f"{engine_id}.json"
        was_placeholder = json.loads(dest_json.read_text()).get("placeholder", True) \
            if dest_json.exists() else True
        old_size = dest_onnx.stat().st_size if dest_onnx.exists() else 0
        new_size = onnx_path.stat().st_size
        tag = "placeholder -> TRAINED" if was_placeholder else "trained -> retrained"
        if not args.dry_run:
            dest_onnx.write_bytes(onnx_path.read_bytes())
            dest_json.write_text(json.dumps(meta, indent=2) + "\n")
        report.append(f"{engine_id}: upgraded ({tag})  {old_size:,} -> {new_size:,} bytes")

    hmm_src = art / "hmm_transition.json"
    if hmm_src.exists():
        errs = check_hmm(hmm_src)
        if errs:
            failures += [f"hmm_transition: {e}" for e in errs]
        else:
            dest = ASSETS / "hmm_transition.json"
            if not args.dry_run:
                dest.write_text(hmm_src.read_text())
            report.append("hmm_transition.json: installed")
    else:
        report.append("hmm_transition.json: not present (optional)")

    print("=" * 64)
    print("SPLOOT ASSET UPGRADE REPORT" + ("  [DRY RUN]" if args.dry_run else ""))
    print("=" * 64)
    for line in report:
        print("  " + line)
    if failures:
        print("\nREFUSED (bundled placeholders kept):")
        for line in failures:
            print("  !! " + line)
        sys.exit(1)
    print("\nAll checks passed.")


if __name__ == "__main__":
    main()
