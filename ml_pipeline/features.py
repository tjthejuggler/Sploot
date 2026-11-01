#!/usr/bin/env python3
"""Sploot feature extraction — NUMERIC PARITY re-implementation of the Kotlin pipeline.

Mirrors (line-for-line where practical):
  app/src/main/java/com/example/sploot/signal/ArtifactFilter.kt
  app/src/main/java/com/example/sploot/signal/EpochBuilder.kt
  app/src/main/java/com/example/sploot/signal/Interpolator.kt        (PCHIP tachogram)
  app/src/main/java/com/example/sploot/signal/respiration/EdrExtractor.kt
  app/src/main/java/com/example/sploot/features/*.kt                (HRV + actigraphy)
  app/src/main/java/com/example/sploot/features/FeatureVectorBuilder.kt

The output is a 127-column frame whose column order MUST equal
assets/features_manifest.json / FeatureVectorBuilder.FEATURE_NAMES
(hard-asserted by validate_feature_names()).

PARITY NOTES (exact float equality is NOT expected; structural + <1% parity is):
  * All algorithms here are the SAME algorithms as the Kotlin code (native LSP,
    native Welch at the LSP grid, Fritsch–Carlson PCHIP, direct O(n^2) SampEn,
    box DFA) — not library substitutes — so differences come only from floating
    point accumulation order (numpy pairwise summation vs sequential loops),
    typically <1e-9 relative.
  * The only places where tiny systematic drift is possible are the long sums
    in LSP/Welch/DFA (thousands of terms): still far below 1% for any realistic
    window.
"""
from __future__ import annotations

import json
import math
import pathlib
from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Tuple

import numpy as np

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST = REPO_ROOT / "app/src/main/assets/features_manifest.json"

FEATURE_VERSION = 1
MIN_BEATS = 10
DAY_MS = 86_400_000
EPOCH_MS = 30_000
WINDOWS: List[Tuple[int, str]] = [(30, ""), (90, "_w90"), (150, "_w150"), (300, "_w300")]

WINDOW_FEATURE_BASE: List[str] = [
    "hrv_mean_ibi", "hrv_sdnn", "hrv_rmssd", "hrv_pnn50", "hrv_sdsd", "hrv_iqr",
    "hrv_vlf_lsp", "hrv_lf_lsp", "hrv_hf_lsp", "hrv_lfnu_lsp", "hrv_hfnu_lsp", "hrv_lfhf_lsp",
    "hrv_vlf_welch", "hrv_lf_welch", "hrv_hf_welch", "hrv_lfhf_welch",
    "hrv_sd1", "hrv_sd2", "hrv_sd2_sd1", "hrv_sampen", "hrv_dfa_alpha1", "hrv_dfa_alpha2",
    "resp_rate", "resp_rrv", "resp_confidence", "resp_from_acc",
    "acc_mean", "acc_var", "acc_mad", "acc_enmo", "acc_time_since_movement",
]

FEATURE_NAMES: List[str] = [base + suffix for _, suffix in WINDOWS for base in WINDOW_FEATURE_BASE] + [
    "clock_minutes_since_start", "clock_tod_sin", "clock_tod_cos",
]

VLF_LO, VLF_HI, LF_HI, HF_HI = 0.0033, 0.04, 0.15, 0.4
LSP_DF = 0.002
WELCH_SEGMENT = 64
RESP_BAND_LO, RESP_BAND_HI, EDR_DF = 0.13, 0.50, 0.005
MIN_ACC_SAMPLES_EDR = 128
SUB_WINDOW_SEC = 10.0
MOVEMENT_ENMO_THRESHOLD_MG = 22.0


def validate_feature_names(manifest_path: pathlib.Path = DEFAULT_MANIFEST) -> List[str]:
    """Load features_manifest.json and hard-assert list equality with FEATURE_NAMES."""
    m = json.loads(pathlib.Path(manifest_path).read_text())
    names = m["features"]
    assert m["version"] == FEATURE_VERSION, f"manifest version {m['version']} != {FEATURE_VERSION}"
    assert names == FEATURE_NAMES, (
        f"feature order drift: manifest has {len(names)} names, "
        f"ml_pipeline has {len(FEATURE_NAMES)}; first diff at "
        f"{next(i for i, (a, b) in enumerate(zip(names + [None] * 999, FEATURE_NAMES + [None] * 999)) if a != b)}"
    )
    assert len(names) == 127, f"expected 127 features, manifest has {len(names)}"
    return names


# ----------------------------------------------------------------------------
# Small helpers (Kotlin parity: median = sorted mid / mean-of-mids)
# ----------------------------------------------------------------------------

def _median(v: np.ndarray) -> float:
    s = np.sort(v)
    m = s.size // 2
    return float(s[m]) if s.size % 2 == 1 else float((s[m - 1] + s[m]) / 2.0)


def _quantile7(x: np.ndarray, q: float) -> float:
    """Linear-interpolated quantile (type 7) as HrvTimeDomain.quantile."""
    s = np.sort(x)
    h = (s.size - 1) * q
    lo, hi = math.floor(h), math.ceil(h)
    return float(s[lo]) if lo == hi else float(s[lo] + (h - lo) * (s[hi] - s[lo]))


# ----------------------------------------------------------------------------
# ArtifactFilter.kt
# ----------------------------------------------------------------------------

@dataclass
class IbiSample:
    timestamp_ms: int
    interval_ms: int
    blocker_bit: Optional[bool] = None
    skin_contact: Optional[bool] = None
    error: Optional[int] = None


@dataclass
class CleanBeat:
    timestamp_ms: int
    ibi_ms: float


MIN_IBI_MS, MAX_IBI_MS, DEVIATION_THRESHOLD, ERROR_LIMIT_MS = 300, 2200, 0.20, 100


def artifact_filter(samples: Sequence[IbiSample]) -> Tuple[List[CleanBeat], List[Tuple[int, str]]]:
    """Returns (clean beats, rejected (ts, reason)) — mirrors ArtifactFilter.filter."""
    sorted_samples = sorted(samples, key=lambda s: s.timestamp_ms)
    rejected: List[Tuple[int, str]] = []

    survivors: List[IbiSample] = []
    for s in sorted_samples:  # pass 1: flags + bounds
        if s.blocker_bit is True:
            rejected.append((s.timestamp_ms, "BLOCKER_BIT"))
        elif s.skin_contact is False:
            rejected.append((s.timestamp_ms, "SKIN_CONTACT"))
        elif s.error is not None and s.error > ERROR_LIMIT_MS:
            rejected.append((s.timestamp_ms, "ERROR_FLAG"))
        elif not (MIN_IBI_MS <= s.interval_ms <= MAX_IBI_MS):
            rejected.append((s.timestamp_ms, "OUT_OF_BOUNDS"))
        else:
            survivors.append(s)

    n = len(survivors)
    keep = np.ones(n, dtype=bool)
    vals = np.zeros(5)
    for i in range(n):  # pass 2: 20% vs positional 5-beat moving median
        count = 0
        for j in range(i - 2, i + 3):
            if 0 <= j < n:
                vals[count] = survivors[j].interval_ms
                count += 1
        median = _median(vals[:count])
        x = float(survivors[i].interval_ms)
        if median > 0 and abs(x - median) > DEVIATION_THRESHOLD * median:
            keep[i] = False
            rejected.append((survivors[i].timestamp_ms, "ECTOPIC"))

    clean = [CleanBeat(s.timestamp_ms, float(s.interval_ms)) for i, s in enumerate(survivors) if keep[i]]
    return clean, rejected


# ----------------------------------------------------------------------------
# EpochBuilder.kt
# ----------------------------------------------------------------------------

@dataclass
class AccSample:
    timestamp_ms: int
    x: int
    y: int
    z: int
    sample_rate: int = 0


@dataclass
class EpochData:
    index: int
    start_ms: int
    end_ms: int
    ibis: List[CleanBeat] = field(default_factory=list)
    rejected_count: int = 0
    acc_samples: List[AccSample] = field(default_factory=list)
    hrv_valid: bool = False
    acc_valid: bool = False

    @property
    def total_ibi_count(self) -> int:
        return len(self.ibis) + self.rejected_count


MIN_ACC_SAMPLES_EPOCH = 100


def build_epochs(session_start_ms: int, clean: Sequence[CleanBeat],
                 rejected: Sequence[Tuple[int, str]], acc: Sequence[AccSample],
                 epoch_ms: int = EPOCH_MS) -> List[EpochData]:
    if not clean and not rejected and not acc:
        return []
    max_ts = max(
        clean[-1].timestamp_ms if clean else session_start_ms,
        rejected[-1][0] if rejected else session_start_ms,
        acc[-1].timestamp_ms if acc else session_start_ms,
    )
    n_epochs = int((max_ts - session_start_ms) // epoch_ms) + 1
    by_epoch = [EpochData(i, session_start_ms + i * epoch_ms, session_start_ms + (i + 1) * epoch_ms)
                for i in range(n_epochs)]

    def idx(ts: int) -> int:
        return (ts - session_start_ms) // epoch_ms

    for b in clean:
        i = idx(b.timestamp_ms)
        if 0 <= i < n_epochs:
            by_epoch[i].ibis.append(b)
    for ts, _ in rejected:
        i = idx(ts)
        if 0 <= i < n_epochs:
            by_epoch[i].rejected_count += 1
    for a in acc:
        i = idx(a.timestamp_ms)
        if 0 <= i < n_epochs:
            by_epoch[i].acc_samples.append(a)
    for e in by_epoch:
        total = len(e.ibis) + e.rejected_count
        ratio = len(e.ibis) / total if total else 0.0
        e.hrv_valid = total > 0 and ratio >= 0.60
        e.acc_valid = len(e.acc_samples) >= MIN_ACC_SAMPLES_EPOCH
    return by_epoch


# ----------------------------------------------------------------------------
# HrvTimeDomain.kt
# ----------------------------------------------------------------------------

def hrv_mean_ibi(x: np.ndarray) -> float:
    return float(np.mean(x)) if x.size else math.nan


def hrv_sdnn(x: np.ndarray) -> float:
    if x.size < 2:
        return math.nan
    return float(np.sqrt(np.mean((x - x.mean()) ** 2)))


def hrv_rmssd(x: np.ndarray) -> float:
    if x.size < 2:
        return math.nan
    d = np.diff(x)
    return float(np.sqrt(np.mean(d * d)))


def hrv_sdsd(x: np.ndarray) -> float:
    if x.size < 3:
        return math.nan
    d = np.diff(x)
    return float(np.sqrt(np.mean((d - d.mean()) ** 2)))


def hrv_pnn50(x: np.ndarray) -> float:
    if x.size < 2:
        return math.nan
    return 100.0 * np.count_nonzero(np.abs(np.diff(x)) > 50.0) / (x.size - 1)


def hrv_iqr(x: np.ndarray) -> float:
    if x.size < 4:
        return math.nan
    return _quantile7(x, 0.75) - _quantile7(x, 0.25)


# ----------------------------------------------------------------------------
# HrvFrequencyDomain.kt — native LSP + native Welch at the LSP grid
# ----------------------------------------------------------------------------

def lomb_scargle(t: np.ndarray, y: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    n = t.size
    n_bins = int(HF_HI / LSP_DF) + 1
    freqs = np.arange(n_bins) * LSP_DF
    power = np.zeros(n_bins)
    if n < 4:
        return freqs, power
    yc = y - y.mean()
    variance = float(np.sum(yc * yc)) / n
    for k in range(n_bins):
        f = freqs[k]
        if f <= 0.0:
            continue
        w = 2 * math.pi * f
        tau = math.atan2(np.sin(2 * w * t).sum(), np.cos(2 * w * t).sum()) / (2 * w)
        a = w * (t - tau)
        c, s = np.cos(a), np.sin(a)
        cc, ss, sc = float((c * c).sum()), float((s * s).sum()), float((s * c).sum())
        num1, num2 = float((yc * c).sum()), float((yc * s).sum())
        den = cc * ss - sc * sc
        if den == 0.0:
            continue
        p = (ss * num1 * num1 + cc * num2 * num2 - 2 * sc * num1 * num2) / den
        power[k] = p / (2.0 * variance) * variance  # == p / 2 (kept to mirror Kotlin)
    return freqs, power


def welch(x: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    n_bins = int(HF_HI / LSP_DF) + 1
    freqs = np.arange(n_bins) * LSP_DF
    acc = np.zeros(n_bins)
    seg_count = 0
    seg = WELCH_SEGMENT
    w = 0.5 * (1 - np.cos(2 * math.pi * np.arange(seg) / (seg - 1)))
    wss = float((w * w).sum())
    n = x.size
    i = 0
    while i < n:
        if np.isnan(x[i]):
            i += 1
            continue
        j = i
        while j < n and not np.isnan(x[j]):
            j += 1
        s = i
        while s + seg <= j:
            block = x[s:s + seg]
            v = (block - block.mean()) * w
            k = np.arange(1, n_bins)
            a = 2 * math.pi * freqs[1:, None] * np.arange(seg)[None, :] / 4.0
            re = (v * np.cos(a)).sum(axis=1)
            im = (v * np.sin(a)).sum(axis=1)
            acc[1:] += (re * re + im * im) / (4.0 * wss)
            seg_count += 1
            s += seg // 2
        i = j
    return freqs, (acc / seg_count if seg_count else np.zeros(n_bins))


def _band_integrate(freqs: np.ndarray, power: np.ndarray, lo: float, hi: float) -> float:
    if freqs.size < 2:
        return 0.0
    total = 0.0
    for k in range(freqs.size - 1):
        f0, f1 = freqs[k], freqs[k + 1]
        if f1 <= lo or f0 >= hi:
            continue
        p0, p1 = power[k], power[k + 1]
        if f0 < lo:
            p0 = power[k] + (power[k + 1] - power[k]) * (lo - f0) / (f1 - f0)
        if f1 > hi:
            p1 = power[k] + (power[k + 1] - power[k]) * (hi - f0) / (f1 - f0)
        a, b = max(f0, lo), min(f1, hi)
        if b > a:
            total += 0.5 * (p0 + p1) * (b - a)
    return total


def band_powers(freqs: np.ndarray, power: np.ndarray) -> dict:
    vlf = _band_integrate(freqs, power, VLF_LO, VLF_HI)
    lf = _band_integrate(freqs, power, VLF_HI, LF_HI)
    hf = _band_integrate(freqs, power, LF_HI, HF_HI)
    tot = lf + hf
    return {
        "vlf": vlf, "lf": lf, "hf": hf,
        "lf_nu": 100.0 * lf / tot if tot > 0 else math.nan,
        "hf_nu": 100.0 * hf / tot if tot > 0 else math.nan,
        "lf_hf": lf / hf if hf > 0 else math.nan,
    }


# ----------------------------------------------------------------------------
# HrvNonlinear.kt
# ----------------------------------------------------------------------------

def poincare(x: np.ndarray) -> Tuple[float, float, float]:
    if x.size < 3:
        return math.nan, math.nan, math.nan
    n = x.size
    var_x = float(((x - x.mean()) ** 2).sum()) / (n - 1)
    var_d = float((np.diff(x) ** 2).sum()) / (n - 1)
    sd1 = math.sqrt(var_d / 2.0)
    sd2 = math.sqrt(2.0 * var_x - sd1 * sd1)
    return sd1, sd2, (sd2 / sd1 if sd1 > 0 else math.nan)


def _count_matches(x: np.ndarray, m: int, r: float) -> int:
    n = x.size
    w = np.lib.stride_tricks.sliding_window_view(x, m)[: n - m]  # only full m-tuples
    # Chebyshev distance between all i<j pairs (self-matches excluded by i<j).
    d = np.max(np.abs(w[:, None, :] - w[None, :, :]), axis=2)
    iu = np.triu_indices(w.shape[0], k=1)
    return int(np.count_nonzero(d[iu] <= r))


def sample_entropy(x: np.ndarray) -> float:
    if x.size < 5:
        return math.nan
    sd = hrv_sdnn(x)
    if sd <= 0:
        return math.nan
    r = 0.2 * sd
    a = _count_matches(x, 2, r)
    b = _count_matches(x, 3, r)
    if a == 0 or b == 0:
        return math.nan
    return -math.log(b / a)


def _detrend_rss(y: np.ndarray, s: int, length: int) -> float:
    xv = np.arange(length, dtype=float)
    yv = y[s:s + length]
    A = np.vstack([xv, np.ones(length)]).T
    slope, intercept = np.linalg.lstsq(A, yv, rcond=None)[0]
    resid = yv - (intercept + slope * xv)
    return float((resid * resid).sum())


def dfa(x: np.ndarray, boxes: Sequence[int]) -> float:
    usable = [b for b in boxes if b >= 4 and x.size >= 2 * b]
    if len(usable) < 3:
        return math.nan
    n = x.size
    profile = np.cumsum(x - x.mean())
    log_n, log_f = [], []
    for box in usable:
        n_boxes = n // box
        rss = sum(_detrend_rss(profile, b * box, box) for b in range(n_boxes))
        f = math.sqrt(rss / (n_boxes * box))
        if f > 0:
            log_n.append(math.log(box))
            log_f.append(math.log(f))
    if len(log_n) < 3:
        return math.nan
    return float(np.polyfit(log_n, log_f, 1)[0])


def dfa_alpha1(x: np.ndarray) -> float:
    return dfa(x, [4, 5, 6, 8, 10, 13, 16, 21, 32, 42, 64])


def dfa_alpha2(x: np.ndarray) -> float:
    return dfa(x, [64, 80, 100, 128, 160, 200, 250, 300])


# ----------------------------------------------------------------------------
# Interpolator.kt — PCHIP 4 Hz tachogram
# ----------------------------------------------------------------------------

def pchip_slopes(t: np.ndarray, y: np.ndarray) -> np.ndarray:
    n = y.size
    if n < 2:
        return np.zeros(n)
    d = np.diff(y) / np.diff(t)
    m = np.zeros(n)
    m[0], m[-1] = d[0], d[-1]
    for i in range(1, n - 1):
        if d[i - 1] * d[i] <= 0:
            m[i] = 0.0
        else:
            h_prev = t[i] - t[i - 1]
            h_next = t[i + 1] - t[i]
            w1 = 2 * h_next + h_prev
            w2 = h_next + 2 * h_prev
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
    return m


def tachogram(beats: Sequence[CleanBeat], start_ms: int, end_ms: int,
              sample_rate_hz: float = 4.0, gap_beats: int = 2) -> np.ndarray:
    """4 Hz PCHIP-interpolated tachogram with NaN gaps (Interpolator.tachogram)."""
    assert end_ms > start_ms
    n = len(beats)
    total = int((end_ms - start_ms) / 1000.0 * sample_rate_hz) + 1
    out = np.full(total, math.nan)
    if n < 2:
        return out
    t = np.array([(b.timestamp_ms - start_ms) / 1000.0 for b in beats])
    y = np.array([b.ibi_ms for b in beats])
    nominal = _median(y)
    gap_limit = (gap_beats + 1) * nominal / 1000.0

    segments: List[Tuple[int, int]] = []
    seg_start = 0
    for i in range(n - 1):
        if t[i + 1] - t[i] > gap_limit:
            if i > seg_start:
                segments.append((seg_start, i))
            seg_start = i + 1
    if n - 1 > seg_start:
        segments.append((seg_start, n - 1))

    for lo_i, hi_i in segments:
        ts, ys = t[lo_i:hi_i + 1], y[lo_i:hi_i + 1]
        h = pchip_slopes(ts, ys)
        lo = max(int(ts[0] * sample_rate_hz), 0)
        hi = min(int(ts[-1] * sample_rate_hz), total - 1)
        k = 0
        for idx in range(lo, hi + 1):
            x = idx / sample_rate_hz
            while k < ts.size - 2 and x > ts[k + 1]:
                k += 1
            h_seg = ts[k + 1] - ts[k]
            if h_seg <= 0:
                continue
            s = (x - ts[k]) / h_seg
            s2, s3 = s * s, s * s * s
            h00 = 2 * s3 - 3 * s2 + 1
            h10 = s3 - 2 * s2 + s
            h01 = -2 * s3 + 3 * s2
            h11 = s3 - s2
            out[idx] = h00 * ys[k] + h10 * h_seg * h[k] + h01 * ys[k + 1] + h11 * h_seg * h[k + 1]
    return out


# ----------------------------------------------------------------------------
# EdrExtractor.kt
# ----------------------------------------------------------------------------

def _acc_magnitude(samples: Sequence[AccSample]) -> np.ndarray:
    return np.array([math.sqrt(s.x * s.x + s.y * s.y + s.z * s.z) for s in samples])


def _spread(freqs: np.ndarray, power: np.ndarray) -> float:
    mask = (freqs >= RESP_BAND_LO) & (freqs <= RESP_BAND_HI)
    f, p = freqs[mask], power[mask]
    m0 = p.sum()
    if m0 <= 0:
        return math.nan
    mean = float((p * f).sum() / m0)
    return math.sqrt(max(float((p * f * f).sum() / m0) - mean * mean, 0.0))


def _rsa_estimate(freqs: np.ndarray, power: np.ndarray):
    mask = (freqs >= RESP_BAND_LO) & (freqs <= RESP_BAND_HI)
    total = float(power[mask].sum())
    idx = np.nonzero(mask)[0]
    if idx.size == 0 or total <= 0:
        return (math.nan, 0.0, math.nan)
    best = idx[np.argmax(power[mask])]
    return (float(freqs[best]), float(power[best] / total), _spread(freqs, power))


def _acc_band_peak(samples: Sequence[AccSample]):
    n = len(samples)
    if n < MIN_ACC_SAMPLES_EDR:
        return None
    mag = _acc_magnitude(samples)
    mean = mag.mean()
    dt = (samples[-1].timestamp_ms - samples[0].timestamp_ms) / 1000.0
    fs = (n - 1) / dt if dt > 0 else 0.0
    if fs <= 0:
        return None
    n_bins = int((RESP_BAND_HI - RESP_BAND_LO) / EDR_DF) + 1
    freqs = RESP_BAND_LO + np.arange(n_bins) * EDR_DF
    v = mag - mean
    a = 2 * math.pi * freqs[:, None] * np.arange(n)[None, :] / fs
    power = ((v * np.cos(a)).sum(axis=1) ** 2 + (v * np.sin(a)).sum(axis=1) ** 2) / n
    total = float(power.sum())
    if total <= 0:
        return None
    best = int(np.argmax(power))
    f_mean = float((power * freqs).sum() / total)
    sd = math.sqrt(max(float((power * freqs * freqs).sum() / total) - f_mean * f_mean, 0.0))
    return float(freqs[best]), float(power[best] / total), sd


def edr_estimate(lsp_freqs: np.ndarray, lsp_power: np.ndarray,
                 acc_samples: Sequence[AccSample]):
    """Returns (rate_brpm, rrv_brpm, confidence, from_acc) — EdrExtractor.estimate."""
    rsa_rate, rsa_conf, rsa_spread = _rsa_estimate(lsp_freqs, lsp_power)
    rsa = None if math.isnan(rsa_rate) else (rsa_rate, rsa_conf, rsa_spread * 60.0, False)

    acc = None
    if len(acc_samples) >= MIN_ACC_SAMPLES_EDR:
        full = _acc_band_peak(acc_samples)
        if full is not None:
            rates = []
            t0 = acc_samples[0].timestamp_ms
            win_ms = int(SUB_WINDOW_SEC * 1000)
            w = 0
            while t0 + (w + 1) * win_ms <= acc_samples[-1].timestamp_ms:
                sub = [s for s in acc_samples
                       if t0 + w * win_ms <= s.timestamp_ms < t0 + (w + 1) * win_ms]
                p = _acc_band_peak(sub)
                if p is not None:
                    rates.append(p[0] * 60.0)
                w += 1
            if len(rates) >= 2:
                r = np.array(rates)
                rrv = float(np.sqrt(((r - r.mean()) ** 2).mean()))
            else:
                rrv = full[2] * 60.0
            acc = (full[0], full[1], rrv, True)

    chosen = acc if (acc is not None and rsa is not None and acc[1] >= rsa[1]) else (acc if rsa is None else rsa)
    if chosen is None:
        chosen = rsa  # both None -> all-NaN handled below
    if chosen is None:
        return math.nan, math.nan, math.nan, math.nan
    rate, conf, rrv, from_acc = chosen
    return rate * 60.0, rrv, conf, 1.0 if from_acc else 0.0


# ----------------------------------------------------------------------------
# ActigraphyFeatures.kt
# ----------------------------------------------------------------------------

def actigraphy_compute(samples: Sequence[AccSample]) -> dict:
    if not samples:
        return {"mean_mag": math.nan, "var_mag": math.nan, "mad": math.nan,
                "enmo": math.nan, "movement": False}
    mag = _acc_magnitude(samples)
    mean = float(mag.mean())
    var = float(((mag - mean) ** 2).mean())
    med = _median(mag)
    mad = _median(np.abs(mag - med))
    enmo = float(np.clip(mag - 1000.0, 0.0, None).sum() / mag.size)
    return {"mean_mag": mean, "var_mag": var, "mad": mad, "enmo": enmo,
            "movement": enmo > MOVEMENT_ENMO_THRESHOLD_MG}


def time_since_last_movement(enmo_per_epoch: np.ndarray, epoch_index: int) -> float:
    if enmo_per_epoch[epoch_index] > MOVEMENT_ENMO_THRESHOLD_MG:
        return 0.0
    i = epoch_index - 1
    while i >= 0:
        if enmo_per_epoch[i] > MOVEMENT_ENMO_THRESHOLD_MG:
            return float(epoch_index - i)
        i -= 1
    return math.nan


# ----------------------------------------------------------------------------
# FeatureVectorBuilder.kt
# ----------------------------------------------------------------------------

def _window_features(epochs: Sequence[EpochData], epoch_index: int, window_sec: int,
                     enmo_per_epoch: np.ndarray) -> List[Tuple[str, float]]:
    half = window_sec // 60
    lo = max(epoch_index - half, 0)
    hi = min(epoch_index + half, len(epochs) - 1)
    window = epochs[lo:hi + 1]

    beats: List[CleanBeat] = [b for e in window for b in e.ibis]
    acc: List[AccSample] = [a for e in window for a in e.acc_samples]
    ibi = np.array([b.ibi_ms for b in beats])
    accepted = sum(len(e.ibis) for e in window)
    total = sum(e.total_ibi_count for e in window)
    hrv_valid = total > 0 and accepted / total >= 0.60

    def gate(fn):
        return fn(ibi) if (hrv_valid and ibi.size >= MIN_BEATS) else math.nan

    feats: List[Tuple[str, float]] = [
        ("hrv_mean_ibi", gate(lambda x: hrv_mean_ibi(x))),
        ("hrv_sdnn", gate(lambda x: hrv_sdnn(x))),
        ("hrv_rmssd", gate(lambda x: hrv_rmssd(x))),
        ("hrv_pnn50", gate(lambda x: hrv_pnn50(x))),
        ("hrv_sdsd", gate(lambda x: hrv_sdsd(x))),
        ("hrv_iqr", gate(lambda x: hrv_iqr(x))),
    ]

    lsp_freqs = lsp_power = None
    if hrv_valid and ibi.size >= MIN_BEATS:
        t0 = window[0].start_ms
        t = np.array([(b.timestamp_ms - t0) / 1000.0 for b in beats])
        tach = tachogram(beats, window[0].start_ms, window[-1].end_ms)
        lsp_freqs, lsp_power = lomb_scargle(t, ibi)
        _, w_power = welch(tach)
        lp = band_powers(lsp_freqs, lsp_power)
        wp = band_powers(lsp_freqs, w_power)
        feats += [
            ("hrv_vlf_lsp", lp["vlf"]), ("hrv_lf_lsp", lp["lf"]), ("hrv_hf_lsp", lp["hf"]),
            ("hrv_lfnu_lsp", lp["lf_nu"]), ("hrv_hfnu_lsp", lp["hf_nu"]), ("hrv_lfhf_lsp", lp["lf_hf"]),
            ("hrv_vlf_welch", wp["vlf"]), ("hrv_lf_welch", wp["lf"]), ("hrv_hf_welch", wp["hf"]),
            ("hrv_lfhf_welch", wp["lf_hf"]),
        ]
    else:
        feats += [(n, math.nan) for n in
                  ("hrv_vlf_lsp", "hrv_lf_lsp", "hrv_hf_lsp", "hrv_lfnu_lsp", "hrv_hfnu_lsp",
                   "hrv_lfhf_lsp", "hrv_vlf_welch", "hrv_lf_welch", "hrv_hf_welch", "hrv_lfhf_welch")]

    sd1, sd2, ratio = (
        poincare(ibi) if (hrv_valid and ibi.size >= MIN_BEATS) else (math.nan,) * 3)
    feats += [
        ("hrv_sd1", sd1), ("hrv_sd2", sd2), ("hrv_sd2_sd1", ratio),
        ("hrv_sampen", gate(lambda x: sample_entropy(x))),
        ("hrv_dfa_alpha1", gate(lambda x: dfa_alpha1(x))),
        ("hrv_dfa_alpha2", gate(lambda x: dfa_alpha2(x))),
    ]

    if lsp_freqs is not None:
        rate, rrv, conf, from_acc = edr_estimate(lsp_freqs, lsp_power, acc)
        feats += [("resp_rate", rate), ("resp_rrv", rrv), ("resp_confidence", conf),
                  ("resp_from_acc", from_acc)]
    else:
        feats += [("resp_rate", math.nan), ("resp_rrv", math.nan),
                  ("resp_confidence", math.nan), ("resp_from_acc", math.nan)]

    if acc:
        m = actigraphy_compute(acc)
        feats += [
            ("acc_mean", m["mean_mag"]), ("acc_var", m["var_mag"]), ("acc_mad", m["mad"]),
            ("acc_enmo", m["enmo"]),
            ("acc_time_since_movement", time_since_last_movement(enmo_per_epoch, epoch_index)),
        ]
    else:
        feats += [(n, math.nan) for n in
                  ("acc_mean", "acc_var", "acc_mad", "acc_enmo", "acc_time_since_movement")]
    return feats


def build_feature_vector(epochs: Sequence[EpochData], epoch_index: int,
                         session_start_ms: int) -> np.ndarray:
    """127-float vector in FEATURE_NAMES order — FeatureVectorBuilder.build."""
    assert 0 <= epoch_index < len(epochs)
    values = np.full(len(FEATURE_NAMES), math.nan)

    enmo_per_epoch = np.array([
        (lambda a: 0.0 if math.isnan(a["enmo"]) else a["enmo"])(actigraphy_compute(e.acc_samples))
        for e in epochs
    ])

    v = 0
    for window_sec, suffix in WINDOWS:
        for name, value in _window_features(epochs, epoch_index, window_sec, enmo_per_epoch):
            assert FEATURE_NAMES[v] == name + suffix, \
                f"feature order drift at {v}: {FEATURE_NAMES[v]} vs {name + suffix}"
            values[v] = value
            v += 1

    epoch = epochs[epoch_index]
    minutes = (epoch.start_ms - session_start_ms) / 60_000.0
    hours = (epoch.start_ms % DAY_MS) / 3_600_000.0
    values[v] = minutes
    values[v + 1] = math.sin(2 * math.pi * hours / 24.0)
    values[v + 2] = math.cos(2 * math.pi * hours / 24.0)
    assert v + 3 == len(FEATURE_NAMES)
    return values


def build_feature_frame(session_start_ms: int, ibi_samples: Sequence[IbiSample],
                        acc_samples: Sequence[AccSample]):
    """Full pipeline: filter -> epochs -> per-epoch 127-feature pandas DataFrame."""
    import pandas as pd
    clean, rejected = artifact_filter(ibi_samples)
    epochs = build_epochs(session_start_ms, clean, rejected, acc_samples)
    rows = [build_feature_vector(epochs, i, session_start_ms) for i in range(len(epochs))]
    df = pd.DataFrame(np.array(rows) if rows else np.zeros((0, len(FEATURE_NAMES))),
                      columns=FEATURE_NAMES)
    df.insert(0, "epoch_index", range(len(epochs)))
    df.insert(1, "start_ms", [e.start_ms for e in epochs])
    return df, epochs


if __name__ == "__main__":
    names = validate_feature_names()
    print(f"manifest OK: {len(names)} features, version {FEATURE_VERSION}")
