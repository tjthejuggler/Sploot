#!/usr/bin/env python3
"""Build the training dataset from NSRR SHHS/MESA polysomnography downloads.

Per night:
  1. Read the EDF (pyedflib) -> ECG channel (e.g. "ECG"), optional ACC channels.
  2. R-peak detection on band-passed ECG (compact Pan-Tompkins in numpy).
  3. RR intervals -> IbiSample list -> features.py pipeline (ArtifactFilter ->
     EpochBuilder -> FeatureVectorBuilder parity code) => 127 features/epoch.
  4. Staged NSRR annotations XML -> AASM stage per 30 s epoch
     (Wake->0, Stage1/Stage2->1 Light, Stage3/Stage4->2 Deep, REM->3).
  5. Emit parquet shard per subject + dataset_manifest.json.

Usage:
  python ml_pipeline/build_dataset.py --nsrr-dir ~/nsrr/shhs1 --out ml_pipeline/data/shhs1

Notes
-----
* ACC channels exist in MESA (wrist/hip/ankle "M生物"-prefixed labels vary);
  SHHS1 has no usable body ACC, so acc_* features come out NaN there — the
  models are trained on the shared (HRV+resp+clock) feature set, which is
  exactly what the on-device pipeline produces for the chest strap.
* ECG R-peak detection is a minimal Pan-Tompkins (~5-15 Hz bandpass, derivative,
  squaring, 150 ms moving-average integration, 200 ms refractory + adaptive
  threshold). It is intentionally simple; for research-grade RR series swap in
  wfdb.processing.xqrs (flag --xqrs).
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import time
import xml.etree.ElementTree as ET
from typing import Optional

import numpy as np
import pandas as pd

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import features as F  # noqa: E402

STAGE_MAP = {
    "wake|w": 0, "stage1|n1": 1, "stage2|n2": 1, "stage3|n3": 2, "stage4|n4": 2,
    "rem|r": 3, "movement": None, "unscored": None,
}


# ---------------------------------------------------------------------------
# ECG R-peak detection (compact Pan-Tompkins)
# ---------------------------------------------------------------------------

def bandpass_ecg(x: np.ndarray, fs: float) -> np.ndarray:
    # 5-15 Hz rough bandpass via differenced moving averages (fast, dependency-free)
    win_lo = max(int(fs / 15.0), 1)
    win_hi = max(int(fs / 5.0), win_lo + 1)

    def mavg(v, w):
        c = np.cumsum(np.concatenate([[0.0], v]))
        out = (c[w:] - c[:-w]) / w
        return np.concatenate([np.full(w - 1, out[0]), out])

    low = mavg(x, win_hi)   # low-pass (smoothing)
    high = x - mavg(low, win_lo)  # remove very-low drift
    return high


def pan_tompkins(ecg: np.ndarray, fs: float) -> np.ndarray:
    """Return R-peak sample indices."""
    x = bandpass_ecg(ecg.astype(float), fs)
    dx = np.gradient(x)
    sq = dx * dx
    int_win = max(int(0.15 * fs), 2)
    integrated = pd.Series(sq).rolling(int_win, center=True, min_periods=1).mean().to_numpy()

    refractory = int(0.2 * fs)
    peaks: list[int] = []
    threshold = 0.35 * np.max(integrated) if integrated.size else 0.0
    min_gap = refractory
    i = 1
    n = integrated.size
    while i < n - 1:
        if integrated[i] > integrated[i - 1] and integrated[i] >= integrated[i + 1] \
                and integrated[i] > threshold:
            peaks.append(i)
            i += min_gap
            threshold = 0.5 * threshold + 0.5 * 0.25 * integrated[i]
        else:
            i += 1
    return np.array(peaks, dtype=int)


def xqrs_peaks(ecg: np.ndarray, fs: float) -> np.ndarray:
    import wfdb.processing as wp
    return wp.xqrs_detect(sig=ecg.astype(float), fs=fs, verbose=False)


# ---------------------------------------------------------------------------
# EDF reading
# ---------------------------------------------------------------------------

def read_edf(path: pathlib.Path):
    import pyedflib
    f = pyedflib.EdfReader(str(path))
    try:
        labels = [f.getLabel(i) for i in range(f.signals_in_file)]
        info = []
        for i, lab in enumerate(labels):
            info.append((i, lab, f.getSampleFrequency(i)))
        return info, f
    finally:
        pass  # caller closes


def pick_channel(labels_info, patterns):
    for i, lab, fs in labels_info:
        for p in patterns:
            if re.search(p, lab, re.IGNORECASE):
                return i, lab, fs
    return None


# ---------------------------------------------------------------------------
# NSRR staged annotations XML -> per-epoch labels
# ---------------------------------------------------------------------------

def parse_nsrr_stages(xml_path: pathlib.Path, n_epochs: int,
                      epoch_sec: float = 30.0) -> np.ndarray:
    stages = np.full(n_epochs, -1, dtype=int)
    root = ET.parse(xml_path).getroot()
    for ev in root.iter("ScoredEvent"):
        etype = ev.findtext("EventType", "")
        if "Stages" not in etype:
            continue
        concept = ev.findtext("EventConcept", "")
        start = float(ev.findtext("Start", "0"))
        dur = float(ev.findtext("Duration", "0"))
        stage = STAGE_MAP.get(concept.lower())
        if stage is None:
            continue
        e0 = int(start / epoch_sec)
        e1 = int((start + dur) / epoch_sec)
        for e in range(max(e0, 0), min(e1 + 1, n_epochs)):
            stages[e] = stage
    return stages


# ---------------------------------------------------------------------------
# Per-night processing
# ---------------------------------------------------------------------------

def process_night(edf_path: pathlib.Path, xml_path: Optional[pathlib.Path],
                  use_xqrs: bool = False) -> Optional[pd.DataFrame]:
    import pyedflib
    f = pyedflib.EdfReader(str(edf_path))
    try:
        labels_info = [(i, f.getLabel(i), f.getSampleFrequency(i))
                       for i in range(f.signals_in_file)]

        ecg = pick_channel(labels_info, [r"ecg", r"ekg"])
        if ecg is None:
            print(f"  no ECG channel in {edf_path.name}, skipping")
            return None
        ei, elab, efs = ecg
        ecg_sig = f.readSignal(ei)
        start_dt = f.getStartdatetime()
        start_ms = int(start_dt.timestamp() * 1000)

        peaks = xqrs_peaks(ecg_sig, efs) if use_xqrs else pan_tompkins(ecg_sig, efs)
        if peaks.size < 30:
            print(f"  too few R-peaks ({peaks.size}) in {edf_path.name}")
            return None
        rr_ms = np.diff(peaks) / efs * 1000.0
        ibi = [F.IbiSample(int(start_ms + peaks[k + 1] / efs * 1000), int(round(rr_ms[k])))
               for k in range(len(rr_ms))]

        acc: list[F.AccSample] = []
        acc_ch = pick_channel(labels_info, [r"acc.*x|activity", r"^axis.?1"])
        # (Full tri-axial extraction: look for x/y/z triplets)
        if acc_ch:
            ai, _, afs = acc_ch
            base = re.sub(r"[-_ ]?x$", "", acc_ch[1], flags=re.IGNORECASE)
            chans = {}
            for i, lab, sfs in labels_info:
                for ax in "xyz":
                    if lab.lower() == f"{base}{ax}" or lab.lower().endswith(f"-{ax}"):
                        chans[ax] = (i, sfs)
            if len(chans) == 3:
                sigs = {ax: f.readSignal(i) for ax, (i, _) in chans.items()}
                afs = next(iter(chans.values()))[1]
                n = min(len(s) for s in sigs.values())
                for k in range(n):
                    acc.append(F.AccSample(int(start_ms + k / afs * 1000),
                                           int(sigs["x"][k]), int(sigs["y"][k]), int(sigs["z"][k]),
                                           int(afs)))

        df, epochs = F.build_feature_frame(start_ms, ibi, acc)
        n = len(epochs)

        df["subject"] = edf_path.stem
        df["label"] = -1
        if xml_path is not None and xml_path.exists():
            df["label"] = parse_nsrr_stages(xml_path, n)
        return df
    finally:
        f.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--nsrr-dir", required=True,
                    help="dir containing *.edf and *-nsrr.xml staged annotations")
    ap.add_argument("--out", default="ml_pipeline/data/nsrr")
    ap.add_argument("--xqrs", action="store_true", help="use wfdb xqrs instead of Pan-Tompkins")
    ap.add_argument("--limit", type=int, default=0, help="max nights (0 = all)")
    args = ap.parse_args()

    F.validate_feature_names()
    nsrr = pathlib.Path(args.nsrr_dir)
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    edfs = sorted(nsrr.rglob("*.edf"))
    if args.limit:
        edfs = edfs[: args.limit]
    print(f"{len(edfs)} EDFs in {nsrr}")

    shards, all_labels = [], []
    t0 = time.time()
    for k, edf in enumerate(edfs):
        xml = None
        cands = list(nsrr.rglob(edf.stem + "*-nsrr.xml")) or list(nsrr.rglob(edf.stem + "*.xml"))
        if cands:
            xml = cands[0]
        try:
            df = process_night(edf, xml, use_xqrs=args.xqrs)
        except Exception as e:  # noqa: BLE001
            print(f"  !! {edf.name}: {e}")
            continue
        if df is None or df.empty:
            continue
        shard = out / f"{edf.stem}.parquet"
        df.to_parquet(shard, index=False)
        shards.append(shard.name)
        all_labels.extend(df["label"].tolist())
        done, tot = k + 1, len(edfs)
        print(f"[{done}/{tot}] {edf.stem}: {len(df)} epochs "
              f"({(time.time()-t0)/done:.1f}s/night)")

    labels = np.array(all_labels)
    manifest = {
        "featureVersion": F.FEATURE_VERSION,
        "numFeatures": len(F.FEATURE_NAMES),
        "shards": shards,
        "nEpochs": int(labels.size),
        "labelCounts": {str(s): int(c) for s, c in zip(*np.unique(labels, return_counts=True))},
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    (out / "dataset_manifest.json").write_text(json.dumps(manifest, indent=2))
    print(json.dumps(manifest["labelCounts"], indent=2))
    print(f"wrote {len(shards)} shards -> {out}")


if __name__ == "__main__":
    main()
