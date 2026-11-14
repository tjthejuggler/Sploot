#!/usr/bin/env python3
"""Stream the PhysioNet/CinC Challenge-2018 *training* PSG set (994 nights, open access)
and keep only: R-peak IBI series (from the ECG channel) + 30 s stage labels.
Each ~133 MB record is deleted right after extraction (peak disk use < 1 GB).

Output per night (same format as fetch_hmc.py):  <out>/<tr03-0005>.npz
    t_ms (int64, beat time since recording start), ibi_ms (int32), labels (int8 per 30 s epoch;
    0 Wake, 1 Light(N1+N2), 2 Deep(N3), 3 REM, -1 unscored)

Designed to run on the remote VPS so the metered home connection is not used:
    python fetch_c18.py --out /srv/sploot-ml/data/c18 --workers 3
"""
from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile
import urllib.request
from concurrent.futures import ProcessPoolExecutor

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from fetch_hmc import detect_r_peaks  # noqa: E402

BASE = "https://physionet.org/files/challenge-2018/1.0.0/"
FS = 200.0
EPOCH_SAMPLES = int(FS * 30)


def download(url: str, dst: pathlib.Path) -> None:
    for attempt in range(5):
        try:
            urllib.request.urlretrieve(url, dst)
            return
        except Exception:  # noqa: BLE001
            if attempt == 4:
                raise


def stage_labels(arousal_mat: pathlib.Path) -> np.ndarray:
    import h5py
    with h5py.File(arousal_mat, "r") as f:
        ss = f["data/sleep_stages"]
        names = ["wake", "nonrem1", "nonrem2", "nonrem3", "rem", "undefined"]
        arr = np.stack([np.asarray(ss[k]).reshape(-1) for k in names]).astype(np.uint8)
    n_ep = arr.shape[1] // EPOCH_SAMPLES
    arr = arr[:, : n_ep * EPOCH_SAMPLES].reshape(6, n_ep, EPOCH_SAMPLES).sum(axis=2)
    # merge N1+N2 -> Light
    votes = np.stack([arr[0], arr[1] + arr[2], arr[3], arr[4]])      # W, L, D, R
    y = votes.argmax(axis=0).astype(np.int8)
    y[(votes.sum(axis=0) < EPOCH_SAMPLES * 0.5)] = -1                 # mostly undefined
    return y


def process(rec: str, out: str) -> str:
    import scipy.io as sio
    name = rec.rsplit("/", 1)[-1]
    out_p = pathlib.Path(out)
    dst = out_p / f"{name}.npz"
    if dst.exists():
        return f"{name}: cached"
    with tempfile.TemporaryDirectory(dir=out_p) as td:
        td_p = pathlib.Path(td)
        try:
            hea, mat, aro = td_p / "r.hea", td_p / "r.mat", td_p / "r-arousal.mat"
            download(f"{BASE}{rec}.hea", hea)
            ecg_row = None
            for i, line in enumerate(hea.read_text().splitlines()[1:]):
                if line.split()[-1].upper() == "ECG":
                    ecg_row = i
            if ecg_row is None:
                return f"{name}: no ECG row"
            download(f"{BASE}{rec}-arousal.mat", aro)
            labels = stage_labels(aro)
            download(f"{BASE}{rec}.mat", mat)
            ecg = sio.loadmat(mat)["val"][ecg_row].astype(np.float64)
            peaks = detect_r_peaks(ecg, FS)
            t_ms = (peaks / FS * 1000).astype(np.int64)
            ibi = np.diff(t_ms).astype(np.int32)
            np.savez_compressed(dst, t_ms=t_ms[1:], ibi_ms=ibi, labels=labels)
            return (f"{name}: {peaks.size} beats, HR {60000/np.median(ibi):.0f}, "
                    f"{(labels >= 0).sum()}/{labels.size} labelled epochs")
        except Exception as e:  # noqa: BLE001
            return f"{name}: FAILED {e!r}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=".tmp/c18")
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    recs = urllib.request.urlopen(BASE + "RECORDS", timeout=60).read().decode().split()
    recs = [r for r in recs if r.startswith("training/")]
    if a.limit:
        recs = recs[: a.limit]
    print(f"{len(recs)} training records", flush=True)
    ok = 0
    with ProcessPoolExecutor(a.workers) as ex:
        for i, msg in enumerate(ex.map(process, recs, [str(out)] * len(recs)), 1):
            ok += "FAILED" not in msg and "no ECG" not in msg
            print(f"[{i}/{len(recs)}] {msg}", flush=True)
    print(f"done: {ok}/{len(recs)} ok", flush=True)


if __name__ == "__main__":
    main()
