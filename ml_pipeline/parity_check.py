#!/usr/bin/env python3
"""Kotlin <-> Python feature parity check.

1. Generates the SAME deterministic synthetic session as
   app/src/test/java/com/example/sploot/FeatureParityGoldenTest.kt
   (identical integer LCG, mod 2^31).
2. Runs it through ml_pipeline.features.build_feature_frame().
3. Obtains the Kotlin golden vectors either from a cached golden JSON or by
   invoking `./gradlew :app:testDebugUnitTest --tests '*FeatureParityGoldenTest*'`
   and scraping the system-out block of the JUnit XML.
4. Compares all epochs x 127 features: both-NaN counts as equal; otherwise
   relative+absolute tolerance per feature. Exit 1 on failure.

Tolerance choice: the default pass threshold is 1% relative (2% for
near-zero values via a mixed rel/abs rule), far above the expected ~1e-9
accumulation-order drift. Exact float parity is NOT required.
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import subprocess
import sys
import time

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import features as F  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
GOLDEN_PATH = pathlib.Path(__file__).resolve().parent / "golden" / "features_golden.json"
TEST_XML_GLOB = "app/build/test-results/testDebugUnitTest/TEST-com.example.sploot.FeatureParityGoldenTest.xml"

SESSION_START_MS = 1_735_712_345_000
DURATION_MS = 420_000  # 14 epochs; streams stop 5 s early (matches Kotlin test)
DATA_END_MS = SESSION_START_MS + DURATION_MS - 5_000
MASK31 = (1 << 31) - 1


class Lcg:
    """Identical to the Kotlin Lcg in FeatureParityGoldenTest."""

    def __init__(self, x: int = 1_234_567_89):
        self.x = x

    def next(self) -> int:
        self.x = (1_103_515_245 * self.x + 12_345) & MASK31
        return self.x

    def noise(self, amp: int) -> int:
        return (self.next() % (2 * amp + 1)) - amp


def synthetic_session():
    rng = Lcg()
    ibis = []
    ts = SESSION_START_MS
    i = 0
    while ts < DATA_END_MS:
        interval = (900.0 + 60.0 * math.sin(2 * math.pi * i / 55.0)
                    + 15.0 * math.sin(2 * math.pi * i / 11.0)
                    + rng.noise(10))
        if i % 17 == 5:
            interval *= 1.3
        if i % 101 == 7:
            interval = 2600.0
        iv = int(max(300, min(3000, int(interval))))
        ts += iv
        ibis.append(F.IbiSample(
            ts, iv,
            blocker_bit=(i % 23 == 3),
            skin_contact=True,
            error=200 if i % 37 == 11 else 0,
        ))
        i += 1

    acc = []
    acc_hz = 50
    k = 0
    acc_ts = SESSION_START_MS
    while acc_ts < DATA_END_MS:
        movement = 300 if 500 <= (k % 700) <= 560 else 0
        x = int(20 + rng.noise(15) + movement // 3)
        y = int(-40 + rng.noise(15))
        z = int(990 + rng.noise(15) + movement + int(8.0 * math.sin(2 * math.pi * 0.23 * k / acc_hz)))
        acc.append(F.AccSample(acc_ts, x, y, z, acc_hz))
        acc_ts += 1000 // acc_hz
        k += 1
    return ibis, acc


def fetch_golden(force: bool) -> dict:
    if GOLDEN_PATH.exists() and not force:
        return json.loads(GOLDEN_PATH.read_text())
    print("running Kotlin golden test (gradle) ...")
    subprocess.run(
        ["./gradlew", ":app:testDebugUnitTest", "--tests", "*FeatureParityGoldenTest*", "--quiet"],
        cwd=REPO, check=True,
    )
    xml = REPO / TEST_XML_GLOB
    text = xml.read_text()
    s = text.split("===SPLOOT_PARITY_GOLDEN_BEGIN===", 1)[1]
    s = s.split("===SPLOOT_PARITY_GOLDEN_END===", 1)[0]
    s = s.replace(">", ">").replace("<", "<").replace("&", "&")
    # strip a CDATA wrapper if (and only if) present
    if "![CDATA[" in s:
        s = s.replace("![CDATA[", "", 1)
        s = s.rsplit("]]", 1)[0]
    start = s.index("{")
    end = s.rindex("}") + 1
    golden = json.loads(s[start:end])
    GOLDEN_PATH.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN_PATH.write_text(json.dumps(golden))
    print(f"golden cached -> {GOLDEN_PATH}")
    return golden


def compare(py: np.ndarray, kt: np.ndarray, rel_tol: float, abs_tol: float):
    assert py.shape == kt.shape, f"shape mismatch {py.shape} vs {kt.shape}"
    py_nan, kt_nan = np.isnan(py), np.isnan(kt)
    nan_mismatch = int(np.sum(py_nan != kt_nan))
    both = ~py_nan & ~kt_nan
    diff = np.abs(py - kt)
    denom = np.maximum(np.abs(kt), 1e-12)
    reldiff = np.where(both, diff / denom, 0.0)
    ok = nan_mismatch == 0 and np.all((diff <= abs_tol) | (reldiff <= rel_tol) | ~both)
    max_abs = float(np.nanmax(np.where(both, diff, 0.0))) if both.any() else 0.0
    max_rel = float(np.nanmax(reldiff)) if reldiff.any() else 0.0

    print(f"epochs x features compared : {py.shape[0]} x {py.shape[1]}")
    print(f"NaN pattern mismatches     : {nan_mismatch}")
    print(f"max |abs diff| (finite)    : {max_abs:.3e}")
    print(f"max rel diff (finite)      : {max_rel:.3e}")
    print(f"tolerance: rel {rel_tol:.1%} / abs {abs_tol:.1e}  ->  "
          + ("PASS" if ok else "FAIL"))

    if not ok:
        bad = np.argwhere((diff > abs_tol) & (reldiff > rel_tol) & both)
        nan_bad = np.argwhere(py_nan != kt_nan)[:10]
        for e, j in bad[:15]:
            print(f"  epoch {e} {F.FEATURE_NAMES[j]}: py={py[e, j]!r} kt={kt[e, j]!r}")
        for e, j in nan_bad:
            print(f"  epoch {e} {F.FEATURE_NAMES[j]}: NaN pattern differs")
    return ok


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--golden", help="golden JSON path (default: cached / gradle)")
    ap.add_argument("--regen-golden", action="store_true")
    ap.add_argument("--rel-tol", type=float, default=0.01)
    ap.add_argument("--abs-tol", type=float, default=1e-6)
    args = ap.parse_args()

    global GOLDEN_PATH
    if args.golden:
        GOLDEN_PATH = pathlib.Path(args.golden)

    F.validate_feature_names()
    ibis, acc = synthetic_session()
    t0 = time.time()
    df, _ = F.build_feature_frame(SESSION_START_MS, ibis, acc)
    print(f"python frame: {df.shape} in {time.time() - t0:.2f}s")

    golden = fetch_golden(force=args.regen_golden)
    kt = np.array([[math.nan if v is None else float(v) for v in row]
                   for row in golden["features"]])
    py = df[F.FEATURE_NAMES].to_numpy()
    if py.shape != kt.shape:
        sys.exit(f"epoch count mismatch: python {py.shape} vs kotlin {kt.shape} "
                 f"(regen golden with --regen-golden)")

    ok = compare(py, kt, args.rel_tol, args.abs_tol)
    # strict-informative second look: report drift at 1e-6 for documentation
    if ok:
        print("(informational) 1e-6-level check:", end=" ")
        compare(py, kt, 1e-6, 1e-9)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
