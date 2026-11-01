#!/usr/bin/env python3
"""Evaluation metrics for sleep staging, plus hypnogram-vs-hypnogram benchmarking.

Metrics: epoch concordance (accuracy), Cohen's κ (unweighted), 4x4 confusion
matrix, per-stage precision/recall/F1, multiclass Brier score.

Modes:
  1) shard/dataset mode: run a packaged model over parquet shards
       python ml_pipeline/evaluate.py --model app/src/main/assets/models/sploot-seq.onnx \
            --data ml_pipeline/data/shhs1
  2) --compare-hypnogram OURS.csv REFERENCE.csv
     Each CSV needs per-epoch stage columns: either a 'stage' column (values
     0-3, W/L/D/R, wake/light/deep/rem, or AASM names) or 'epoch,stage' pairs.
     This is the calibration tool for comparing against e.g. a sleep² export.

Usage:
  python ml_pipeline/evaluate.py --compare-hypnogram ours.csv ref.csv
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import numpy as np
import pandas as pd
from sklearn.metrics import (cohen_kappa_score, confusion_matrix, f1_score,
                             precision_recall_fscore_support)

STAGES = ["Wake", "Light", "Deep", "REM"]
NAME_MAP = {
    "0": 0, "w": 0, "wake": 0, "waket": 0,
    "1": 1, "l": 1, "light": 1, "n1": 1, "n2": 1, "stage1": 1, "stage2": 1,
    "2": 2, "d": 2, "deep": 2, "n3": 2, "n4": 2, "stage3": 2, "stage4": 2, "sw": 2,
    "3": 3, "r": 3, "rem": 3,
}


def parse_stage_column(values) -> np.ndarray:
    out = []
    for v in values:
        key = str(v).strip().lower()
        if key not in NAME_MAP:
            raise ValueError(f"unmapped stage value {v!r}")
        out.append(NAME_MAP[key])
    return np.array(out, dtype=int)


def read_hypnogram_csv(path: pathlib.Path) -> np.ndarray:
    df = pd.read_csv(path)
    for col in ("stage", "label", "stage_code", "prediction"):
        if col in df.columns:
            return parse_stage_column(df[col])
    raise ValueError(f"{path}: no stage/label/prediction column found")


def report(y_true: np.ndarray, y_pred: np.ndarray, probs: np.ndarray | None = None) -> None:
    y_true, y_pred = np.asarray(y_true), np.asarray(y_pred)
    n = y_true.size
    acc = float(np.mean(y_true == y_pred))
    kappa = float(cohen_kappa_score(y_true, y_pred))
    cm = confusion_matrix(y_true, y_pred, labels=[0, 1, 2, 3])
    p, r, f1, sup = precision_recall_fscore_support(
        y_true, y_pred, labels=[0, 1, 2, 3], zero_division=0)

    print("=" * 60)
    print(f"epochs: {n}")
    print(f"concordance (accuracy): {acc:.4f}")
    print(f"Cohen's kappa:          {kappa:.4f}")
    if probs is not None:
        onehot = np.eye(4)[y_true]
        brier = float(np.mean(np.sum((probs - onehot) ** 2, axis=1)))
        print(f"multiclass Brier:       {brier:.4f}")
    print("-" * 60)
    print("confusion matrix (rows=truth W/L/D/R, cols=pred):")
    header = "        " + "".join(f"{s:>8}" for s in ("W", "L", "D", "R"))
    print(header)
    for s, row in zip(("W", "L", "D", "R"), cm):
        print(f"{s:>6}   " + "".join(f"{int(v):8d}" for v in row))
    print("-" * 60)
    print(f"{'stage':>8} {'precision':>10} {'recall':>9} {'F1':>8} {'support':>9}")
    for s, pi, ri, fi, si in zip(STAGES, p, r, f1, sup):
        print(f"{s:>8} {pi:10.4f} {ri:9.4f} {fi:8.4f} {int(si):9d}")
    macro = f1_score(y_true, y_pred, labels=[0, 1, 2, 3], average="macro", zero_division=0)
    print(f"{'macro F1':>8} {macro:>28.4f}")
    print("=" * 60)


def run_model_over_shards(model_path: pathlib.Path, data_dir: pathlib.Path,
                          seq: bool) -> None:
    import onnxruntime as ort
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import features as F  # noqa: F401

    sess = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    in_name, out_name = sess.get_inputs()[0].name, sess.get_outputs()[-1].name
    ys, ps = [], []
    for shard in sorted(data_dir.glob("*.parquet")):
        df = pd.read_parquet(shard)
        if "label" not in df:
            continue
        df = df[df["label"] >= 0]
        if df.empty:
            continue
        x = np.nan_to_num(df[F.FEATURE_NAMES].to_numpy(dtype=np.float32), nan=0.0)
        x = x[None, ...] if seq else x  # [1,T,127] or [N,127]
        probs = sess.run([out_name], {in_name: x})[0]
        ys.append(df["label"].to_numpy())
        ps.append(probs if seq else probs)
    y = np.concatenate(ys)
    probs = np.concatenate(ps)
    report(y, probs.argmax(axis=1), probs)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--compare-hypnogram", nargs=2, metavar=("OURS", "REFERENCE"))
    ap.add_argument("--model")
    ap.add_argument("--data")
    ap.add_argument("--seq", action="store_true", help="model takes [1,T,127]")
    args = ap.parse_args()

    if args.compare_hypnogram:
        ours = read_hypnogram_csv(pathlib.Path(args.compare_hypnogram[0]))
        ref = read_hypnogram_csv(pathlib.Path(args.compare_hypnogram[1]))
        if ours.size != ref.size:
            n = min(ours.size, ref.size)
            print(f"WARNING: length mismatch ({ours.size} vs {ref.size}); "
                  f"comparing first {n} epochs")
            ours, ref = ours[:n], ref[:n]
        report(ref, ours)
        return

    if not (args.model and args.data):
        ap.error("need --model and --data, or --compare-hypnogram OURS REF")
    run_model_over_shards(pathlib.Path(args.model), pathlib.Path(args.data), args.seq)


if __name__ == "__main__":
    main()
