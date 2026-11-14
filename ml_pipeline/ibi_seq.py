"""Raw-IBI sequence preprocessing shared by training, offline evaluation and (ported 1:1)
the on-device Kotlin engine.

Why: sleep² (Topalidis et al. 2023, Sensors 23:2390 / 23:9077) feeds a multi-resolution
CNN + BiLSTM/attention network the *raw inter-beat-interval series* resampled to a
constant 2 Hz and z-scored per night - no hand-made HRV features - and trains it on
~9000 PSG nights. We mirror that input representation exactly.

Pipeline (must stay identical in Kotlin `IbiSequencePreprocessor`):
  1. beats (t_ms, ibi_ms) -> drop ibi outside [300, 2000] ms (invalid)
  2. drop beats deviating >20 % from the positional 5-beat median (ectopic / PPG glitch)
  3. 2 Hz grid starting at session start, n_epochs*60 samples; linear interpolation of the
     surviving beats (np.interp, edges held)
  4. channel 0: z-score of the interpolated IBI using night mean/std
     channel 1: quality mask = 1 if a valid beat lies within +-GAP_S of the grid sample
"""
from __future__ import annotations

import numpy as np

FS = 2.0
EPOCH_S = 30
SAMPLES_PER_EPOCH = int(FS * EPOCH_S)  # 60
MIN_IBI, MAX_IBI = 300.0, 2000.0
GAP_S = 5.0


def clean_beats(t_ms: np.ndarray, ibi_ms: np.ndarray):
    t = np.asarray(t_ms, dtype=np.float64)
    x = np.asarray(ibi_ms, dtype=np.float64)
    order = np.argsort(t, kind="stable")
    t, x = t[order], x[order]
    ok = (x >= MIN_IBI) & (x <= MAX_IBI)
    t, x = t[ok], x[ok]
    n = x.size
    if n >= 5:
        pad = np.concatenate([[np.nan, np.nan], x, [np.nan, np.nan]])
        win = np.stack([pad[i:i + n] for i in range(5)], axis=1)
        med = np.nanmedian(win, axis=1)
        keep = np.abs(x - med) <= 0.2 * med
        t, x = t[keep], x[keep]
    return t, x


def to_sequence(t_ms: np.ndarray, ibi_ms: np.ndarray, start_ms: float, n_epochs: int):
    """Returns float32 array [2, n_epochs*60]."""
    t, x = clean_beats(t_ms, ibi_ms)
    n = n_epochs * SAMPLES_PER_EPOCH
    out = np.zeros((2, n), dtype=np.float32)
    if x.size < 10:
        return out
    grid = start_ms + (np.arange(n) + 0.5) * (1000.0 / FS)
    y = np.interp(grid, t, x)
    mu, sd = x.mean(), x.std()
    out[0] = ((y - mu) / max(sd, 1.0)).astype(np.float32)
    idx = np.searchsorted(t, grid)
    left = np.abs(grid - t[np.clip(idx - 1, 0, t.size - 1)])
    right = np.abs(grid - t[np.clip(idx, 0, t.size - 1)])
    out[1] = (np.minimum(left, right) <= GAP_S * 1000.0).astype(np.float32)
    return out
