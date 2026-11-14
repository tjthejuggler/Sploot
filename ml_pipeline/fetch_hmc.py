#!/usr/bin/env python3
"""Stream the open HMC sleep-staging PSG database (PhysioNet, CC-BY 4.0) and keep
only what we need per night: the R-peak/IBI series from the ECG channel and the
30 s AASM labels. The ~100 MB EDF is deleted right after extraction, so disk use
stays tiny.

Output per night: <out>/<SNxxx>.npz  with
    t_ms   int64  beat times in ms since recording start
    ibi_ms int32  inter-beat interval ending at that beat
    labels int8   per-30s epoch (0 Wake,1 Light(N1+N2),2 Deep(N3),3 REM,-1 unscored)

Usage: python ml_pipeline/fetch_hmc.py --out .tmp/hmc --workers 8
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import urllib.request
from concurrent.futures import ProcessPoolExecutor

import numpy as np
from scipy import signal as sps

BASE = "https://physionet.org/files/hmc-sleep-staging/1.1/recordings/"
STAGE = {"W": 0, "N1": 1, "N2": 1, "N3": 2, "R": 3}


def download(url: str, dst: pathlib.Path) -> None:
    for attempt in range(4):
        try:
            urllib.request.urlretrieve(url, dst)
            return
        except Exception:  # noqa: BLE001
            if attempt == 3:
                raise


def detect_r_peaks(ecg: np.ndarray, fs: float) -> np.ndarray:
    """Robust R-peak detector: 5-20 Hz bandpass -> squared derivative envelope ->
    windowed adaptive threshold -> refine on the filtered signal."""
    sos = sps.butter(2, [5.0, 20.0], btype="band", fs=fs, output="sos")
    x = sps.sosfiltfilt(sos, ecg.astype(np.float64))
    env = np.gradient(x) ** 2
    k = max(int(0.12 * fs), 3)
    env = np.convolve(env, np.ones(k) / k, mode="same")
    # adaptive threshold: 30% of the local 95th percentile over 10 s blocks
    blk = int(10 * fs)
    thr = np.zeros_like(env)
    for s in range(0, env.size, blk):
        seg = env[s:s + blk]
        thr[s:s + blk] = 0.3 * np.percentile(seg, 95) if seg.size else 0
    cand, _ = sps.find_peaks(env, height=thr, distance=int(0.28 * fs))
    r = np.empty(cand.size, dtype=np.int64)
    w = int(0.06 * fs)
    ax = np.abs(x)
    for i, c in enumerate(cand):
        lo, hi = max(c - w, 0), min(c + w, x.size)
        r[i] = lo + int(np.argmax(ax[lo:hi]))
    return np.unique(r)


def parse_labels(txt: str) -> np.ndarray:
    rows = []
    for line in txt.splitlines()[1:]:
        m = re.search(r",\s*([\d.]+),\s*([\d.]+),\s*Sleep stage (\w+)", line)
        if m:
            rows.append((float(m.group(1)), STAGE.get(m.group(3), -1)))
    if not rows:
        return np.zeros(0, dtype=np.int8)
    n = int(max(r[0] for r in rows) // 30) + 1
    y = np.full(n, -1, dtype=np.int8)
    for onset, s in rows:
        y[int(onset // 30)] = s
    return y


def process(sn: str, out: str) -> str:
    import pyedflib
    out_p = pathlib.Path(out)
    dst = out_p / f"{sn}.npz"
    if dst.exists():
        return f"{sn}: cached"
    edf = out_p / f"{sn}.edf"
    try:
        download(f"{BASE}{sn}.edf", edf)
        txt = urllib.request.urlopen(f"{BASE}{sn}_sleepscoring.txt", timeout=60).read().decode()
        f = pyedflib.EdfReader(str(edf))
        try:
            labs = [f.getLabel(i) for i in range(f.signals_in_file)]
            idx = next(i for i, l in enumerate(labs) if l.strip().upper() == "ECG")
            fs = f.getSampleFrequency(idx)
            ecg = f.readSignal(idx)
        finally:
            f.close()
        peaks = detect_r_peaks(ecg, fs)
        t_ms = (peaks / fs * 1000).astype(np.int64)
        ibi = np.diff(t_ms).astype(np.int32)
        np.savez_compressed(dst, t_ms=t_ms[1:], ibi_ms=ibi, labels=parse_labels(txt))
        return f"{sn}: {peaks.size} beats, median HR {60000/np.median(ibi):.0f}"
    except Exception as e:  # noqa: BLE001
        return f"{sn}: FAILED {e!r}"
    finally:
        edf.unlink(missing_ok=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=".tmp/hmc")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    recs = urllib.request.urlopen(
        "https://physionet.org/files/hmc-sleep-staging/1.1/RECORDS", timeout=60).read().decode()
    sns = [pathlib.Path(l).stem for l in recs.split() if l.endswith(".edf")]
    if a.limit:
        sns = sns[: a.limit]
    print(f"{len(sns)} recordings", flush=True)
    with ProcessPoolExecutor(a.workers) as ex:
        for msg in ex.map(process, sns, [str(out)] * len(sns)):
            print(msg, flush=True)


if __name__ == "__main__":
    sys.exit(main())
