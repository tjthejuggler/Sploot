#!/usr/bin/env python3
"""Run a trained IBI network on the nights pulled from the phone DB (.tmp/user/*.npz,
produced by .tmp/export_user.py) and print summaries / hypnograms next to the
on-device heuristic output. Writes one CSV per night (epoch,stage) that the app's
A/B Compare screen can import.

Usage: python ml_pipeline/apply_user_nights.py --model ml_pipeline/artifacts/sploot-ibi-cnn.pt
"""
from __future__ import annotations

import argparse
import pathlib
import sys

import numpy as np
import torch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ibi_seq as S  # noqa: E402
from train_ibi_cnn import IbiNet  # noqa: E402

LETTER = "WLDR"


def smooth_modal(p: np.ndarray, k: int = 1) -> np.ndarray:
    """Optional tiny temporal smoothing of probabilities (k epochs each side)."""
    if k <= 0:
        return p
    out = np.empty_like(p)
    for i in range(len(p)):
        out[i] = p[max(0, i - k): i + k + 1].mean(0)
    return out


def summary(st: np.ndarray) -> str:
    n = len(st)
    asleep = st != 0
    first = int(np.argmax(asleep)) if asleep.any() else n
    tst = asleep.sum() / 2
    waso = ((st[first:] == 0).sum()) / 2
    pct = np.bincount(st, minlength=4) / n * 100
    return (f"TIB {n/120:.2f}h  SOL {first/2:.0f}m  TST {tst/60:.2f}h  WASO {waso:.0f}m  "
            f"eff {100*tst*2/n:.0f}%  W/L/D/R % = {pct[0]:.0f}/{pct[1]:.0f}/{pct[2]:.0f}/{pct[3]:.0f}")


def strip(st: np.ndarray, step: int = 10) -> str:
    """One char per `step` epochs (5 min), majority stage."""
    chars = []
    for i in range(0, len(st), step):
        chars.append(LETTER[int(np.bincount(st[i:i + step], minlength=4).argmax())])
    return "".join(chars)


def clock(start_ms: float, epoch: int, tz_hours: float = 3.0) -> str:
    t = np.datetime64(int(start_ms + epoch * 30_000 + tz_hours * 3_600_000), "ms")
    return str(t)[11:16]


def key_times(st: np.ndarray, start_ms: float, min_wake_epochs: int = 8) -> str:
    """Sleep onset, final awakening and wake bouts >= 4 min, in local (UTC+3) clock time."""
    asleep = np.flatnonzero(st != 0)
    if asleep.size == 0:
        return "  never asleep"
    onset, final = int(asleep[0]), int(asleep[-1])
    bouts, i = [], onset
    while i <= final:
        if st[i] == 0:
            j = i
            while j <= final and st[j] == 0:
                j += 1
            if j - i >= min_wake_epochs:
                bouts.append(f"{clock(start_ms, i)}-{clock(start_ms, j)}")
            i = j
        else:
            i += 1
    return (f"  asleep {clock(start_ms, onset)}  final wake {clock(start_ms, final + 1)}  "
            f"wake bouts>=4min: {', '.join(bouts) if bouts else 'none'}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="ml_pipeline/artifacts/sploot-ibi-cnn.pt")
    ap.add_argument("--nights", default=".tmp/user")
    ap.add_argument("--out", default=".tmp/user_out")
    ap.add_argument("--smooth", type=int, default=0)
    ap.add_argument("--wake-bias", type=float, default=0.0,
                    help="constant added to the Wake logit. The model trained on lab PSG under-calls "
                         "brief awakenings on wrist-PPI data; ~1.25 reproduces the user's own sleep2 "
                         "long-run wake statistics (WTSP ~34 min, NOA>=2min ~3/night).")
    a = ap.parse_args()

    model = IbiNet()
    model.load_state_dict(torch.load(a.model, map_location="cpu"))
    model.eval()
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    for f in sorted(pathlib.Path(a.nights).glob("*.npz")):
        z = np.load(f)
        start = float(z["start_ms"])
        heur = z["heur"].astype(int)
        n_ep = int((float(z["end_ms"]) - start) // 30_000)
        x = S.to_sequence(z["t_ms"] - start, z["ibi_ms"], 0.0, n_ep)
        with torch.no_grad():
            lg = model.logits(torch.from_numpy(x)[None])[0]
        lg[:, 0] += a.wake_bias   # calibrate Wake sensitivity (see --wake-bias)
        p = torch.softmax(lg, dim=-1).numpy()
        p = smooth_modal(p, a.smooth)
        st = p.argmax(1)
        np.savetxt(out / f"{f.stem}.csv",
                   np.c_[np.arange(len(st)), st], fmt="%d", delimiter=",", header="epoch,stage",
                   comments="")
        m = min(len(st), len(heur))
        agree = (st[:m] == heur[:m]).mean() * 100
        print(f"\n== {f.stem}  start {np.datetime64(int(start), 'ms')} UTC")
        print(" new  :", summary(st))
        print(" old  :", summary(heur))
        print(" new times:", key_times(st, start))
        print(" old times:", key_times(heur, start))
        print(f" agreement new-vs-old {agree:.0f}%")
        print(" new  :", strip(st))
        print(" old  :", strip(heur[:m]))


if __name__ == "__main__":
    main()
