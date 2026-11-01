#!/usr/bin/env python3
"""Estimate the 4x4 HMM transition matrix + initial priors from training hypnograms.

Counts stage->stage transitions (and first-epoch states) across all labeled
nights in the dataset shards, applies Laplace (add-alpha) smoothing, and
row-normalises. Output: hmm_transition.json in the ViterbiSmoother override
format — a row-stochastic 4x4 matrix A[from][to] plus initial distribution pi,
stage order Wake/Light/Deep/REM, matching the constructor of
app/src/main/java/com/example/sploot/ml/ViterbiSmoother.kt:

    ViterbiSmoother(transitions = arrayOf(doubleArrayOf(...), ...),
                    initial = doubleArrayOf(...))

NOTE: the app currently ships DEFAULT_TRANSITIONS; a small follow-up wiring
step (reading assets/models/hmm_transition.json at engine construction) lets
package_assets.py deliver the learned matrix without changing ViterbiSmoother's
format.

Usage:
  python ml_pipeline/learn_hmm.py --data ml_pipeline/data/shhs1 \
      --out ml_pipeline/artifacts/hmm_transition.json
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

STAGES = ["wake", "light", "deep", "rem"]
N = 4


def count_transitions(data_dir: pathlib.Path):
    a = np.zeros((N, N), dtype=np.int64)
    pi = np.zeros(N, dtype=np.int64)
    nights = 0
    for shard in sorted(data_dir.glob("*.parquet")):
        df = pd.read_parquet(shard)
        if "label" not in df:
            continue
        y = df.loc[df["label"] >= 0, "label"].to_numpy(dtype=int)
        if y.size < 2:
            continue
        nights += 1
        pi[y[0]] += 1
        np.add.at(a, (y[:-1], y[1:]), 1)
    return a, pi, nights


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="ml_pipeline/data/shhs1")
    ap.add_argument("--out", default="ml_pipeline/artifacts/hmm_transition.json")
    ap.add_argument("--alpha", type=float, default=1.0, help="Laplace smoothing count")
    args = ap.parse_args()

    counts, priors, nights = count_transitions(pathlib.Path(args.data))
    if nights == 0:
        sys.exit("no labeled nights found — run build_dataset.py first")

    a = (counts + args.alpha).astype(float)
    a /= a.sum(axis=1, keepdims=True)
    pi = (priors + args.alpha).astype(float)
    pi /= pi.sum()

    result = {
        "version": 1,
        "stages": STAGES,
        "initial": [round(float(v), 6) for v in pi],
        "transitions": [[round(float(v), 6) for v in row] for row in a],
        "laplaceAlpha": args.alpha,
        "nights": nights,
        "source": str(pathlib.Path(args.data).resolve()),
    }
    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2) + "\n")

    print(f"learned from {nights} nights (alpha={args.alpha}):")
    print("      " + "".join(f"{s:>8}" for s in STAGES))
    for s, row in zip(STAGES, result["transitions"]):
        print(f"{s:>5} " + "".join(f"{v:8.4f}" for v in row))
    print("pi : " + " ".join(f"{v:.4f}" for v in result["initial"]))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
