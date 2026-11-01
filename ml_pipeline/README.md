# Sploot ml_pipeline (Phase 7)

Python training pipeline for the on-device sleep-staging models. Produces two
ONNX models + learned HMM transitions that drop into
`app/src/main/assets/models/` with zero Kotlin changes.

Contracts (frozen):
- **Features**: 127 per epoch, order = `FeatureVectorBuilder.FEATURE_NAMES`
  (mirrored in `app/src/main/assets/features_manifest.json`). Windows 30/90/150/300 s
  (31 features each) + 3 clock features. Hard-asserted against the manifest by
  every entry-point script via `features.validate_feature_names()`.
- **sploot-seq** (primary): input `features` float32 `[1, T, 127]` (dynamic T) →
  output `probs` `[T, 4]` softmax probabilities, sidecar
  `{"engineId":"sploot-seq","featureVersion":1,"logits":false}`.
- **sploot-gbdt** (reference): input `[N, 127]` → `[N, 4]` probabilities.
- **Stages**: 0=Wake, 1=Light (N1+N2), 2=Deep (N3/N4), 3=REM. Epoch = 30 s.

## 1. Environment setup

```bash
bash ml_pipeline/setup.sh          # creates ml_pipeline/.venv (CPU torch)
source ml_pipeline/.venv/bin/activate
python ml_pipeline/features.py     # prints manifest validation OK
```

## 2. Feature parity (Kotlin ↔ Python)

`features.py` re-implements ArtifactFilter → EpochBuilder → PCHIP tachogram →
LSP/Welch → nonlinear HRV → EDR → actigraphy → 127-vector, algorithm-for-algorithm.
Parity harness: `app/src/test/.../FeatureParityGoldenTest.kt` prints golden
vectors for a fixed LCG-synthetic session; `parity_check.py` rebuilds it in
Python and compares:

```bash
python ml_pipeline/parity_check.py                 # runs gradle test if needed
python ml_pipeline/parity_check.py --regen-golden  # after changing Kotlin features
```

Tolerance: pass at 1% relative / 1e-6 absolute (expected drift ~1e-9 from
float accumulation order only; see PARITY notes in `features.py` docstring).
The parity report is printed at both 1% and 1e-6 for documentation.

**Verified parity result (2026-11-01, 14 epochs x 127 features):**
0 NaN-pattern mismatches, max abs diff 4.5e-11, max rel diff 1.2e-12 — PASS
even at the 1e-6 informational level. Exact float parity is unnecessary;
this is pure float-accumulation-order noise.

**Latent Kotlin edge discovered while building the harness (documented, not
fixed per Phase 7 scope):** `FeatureVectorBuilder.build()` *throws* its
order-drift `check` when a window fails the HRV validity gate or has <10
beats (the frequency-domain block is skipped entirely instead of emitting
NaN names) — e.g. a trailing partially-filled epoch. The golden-test
generator therefore stops streams 5 s before the epoch boundary so every
epoch is fully populated. A future Kotlin fix would emit the 10 frequency
names as NaN instead of skipping them (Python `features.py` already mirrors
the intended NaN semantics for dataset building).

## 3. NSRR data download (needs YOUR credentials)

Register at https://sleepdata.org, request SHHS (and/or MESA) access, then:

```bash
gem install nsrr && nsrr auth      # paste token
nsrr download shhs/datasets/shhs1 polysomnography/edfs
nsrr download shhs/datasets/shhs1 polysomnography/annotations-events-nsrr -r xml
# or token-URL flow — see ml_pipeline/download_nsrr.sh
```

## 4. Dataset build

```bash
python ml_pipeline/build_dataset.py --nsrr-dir <dir-with-edfs+xmls> \
    --out ml_pipeline/data/shhs1 [--limit 20] [--xqrs]
```

Per night: EDF → ECG R-peaks (compact Pan-Tompkins; `--xqrs` for wfdb XQRS) →
RR intervals → `features.py` pipeline → parquet shard with `label` column
(-1 = unscored). SHHS has no body ACC → `acc_*` NaN there (matches what the
chest-strap-only on-device pipeline produces).

## 5. Training

```bash
# primary sequence model (1D-CNN + BiGRU + temporal attention -> ONNX)
python ml_pipeline/train_sequence_model.py --data ml_pipeline/data/shhs1

# reference GBDT (LightGBM -> ONNX via onnxmltools, validated vs predict_proba)
python ml_pipeline/train_gbdt.py --data ml_pipeline/data/shhs1

# learned HMM transitions (Laplace-smoothed) for ViterbiSmoother
python ml_pipeline/learn_hmm.py --data ml_pipeline/data/shhs1
```

Artifacts land in `ml_pipeline/artifacts/` (`.onnx` + `.json` sidecars +
`hmm_transition.json`). Splits are subject-level in both trainers; class
imbalance handled by weighted loss / balanced class weights.

## 6. Evaluation & calibration

```bash
python ml_pipeline/evaluate.py --model app/src/main/assets/models/sploot-seq.onnx \
    --data ml_pipeline/data/shhs1 --seq

# benchmark two hypnograms (e.g. ours vs sleep² export) — CSVs with a
# stage/label column; values 0-3 or W/L/D/R/N1..N4/REM accepted
python ml_pipeline/evaluate.py --compare-hypnogram ours.csv reference.csv
```

Reports accuracy, Cohen's κ, 4×4 confusion, per-stage P/R/F1, Brier.

## 7. Packaging into the app

```bash
python ml_pipeline/package_assets.py --dry-run   # contract checks + report
python ml_pipeline/package_assets.py             # actually copies
```

Contract gating: shape (dynamic T, [T,4]/[N,4]), row normalisation, sidecar
engineId/featureVersion, HMM row-stochasticity. A model failing any check is
**refused** — bundled placeholders are never overwritten by a broken model.

## 8. Replacing models on device

Two options:

**Rebuild the APK** (placeholders in assets are replaced):
```bash
./gradlew installDebug
```

**Runtime replacement, no reinstall** — push into the app's private model dir;
`ModelLoader.installReplacement` validates before activating (in-app loader or):
```bash
adb push ml_pipeline/artifacts/sploot-seq.onnx \
    /data/data/com.example.sploot/files/models/sploot-seq.onnx
adb push ml_pipeline/artifacts/sploot-seq.json \
    /data/data/com.example.sploot/files/models/sploot-seq.json
# same for sploot-gbdt.onnx/.json
```

`hmm_transition.json` ships in assets; ViterbiSmoother's override format is
row-stochastic 4×4 `transitions` + `initial` (see `learn_hmm.py` docstring) —
wiring the asset read into engine construction is a small follow-up on the
Kotlin side (currently the shipped DEFAULT_TRANSITIONS are used).

## Layout

| file | purpose |
|---|---|
| `requirements.txt`, `setup.sh` | pinned env + venv bootstrap |
| `features.py` | 127-feature parity re-implementation (Kotlin mirror) |
| `parity_check.py` | Kotlin↔Python golden comparison |
| `download_nsrr.sh` | NSRR download flows (gem / token URL) |
| `build_dataset.py` | EDF+XML → parquet shards + manifest |
| `train_sequence_model.py` | CNN+BiGRU+attention → `sploot-seq.onnx` |
| `train_gbdt.py` | LightGBM → `sploot-gbdt.onnx` (+ conversion validation) |
| `learn_hmm.py` | hypnogram transition matrix → `hmm_transition.json` |
| `package_assets.py` | contract-gated copy into app assets |
| `evaluate.py` | metrics + `--compare-hypnogram` benchmark |
| `placeholder_models.py` | Phase 6 placeholder builders (kept) |
