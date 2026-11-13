# Importing a night from a Bluetooth HCI snoop log (Mode C)

Sploot can reconstruct a full overnight raw stream (PPI / ACC / ECG / HR) from the
`btsnoop_hci.log` that Android's Bluetooth stack can be asked to record. The pipeline
(`ble/snoop/`) parses the btsnoop file, reassembles HCI ACL → L2CAP → ATT notifications,
resolves GATT attribute handles to characteristic UUIDs by replaying the discovery
traffic, and decodes Polar PMD frames into the same `RawSample` stream produced by live
SDK ingestion — so everything downstream (signal processing, features, staging) works
unchanged.

## 1. Enable Bluetooth HCI snoop logging on the phone

1. Open **Settings → About phone** and tap **Build number** 7 times to unlock Developer options.
2. Open **Settings → System → Developer options**.
3. Find **Bluetooth HCI snoop log** and toggle it **On**.
4. **Reboot the phone** (most builds only activate the logger after a restart; without
   a reboot the file is often empty or missing).

Notes:
- The log records **all** Bluetooth traffic, including audio profiles; a full night of
  a single BLE sensor is typically a few hundred MB at most.
- On some OEM builds the toggle is named "Enable Bluetooth HCI snoop log" or lives
  under a "Networking" subgroup.

## 2. Record the night

1. Pair your Polar sensor (Verity Sense / H10) with the phone as usual.
2. Start the sleep recording (or simply keep the sensor connected with its streams
   enabled) and leave it for the night.
3. In the morning, stop the recording and keep Bluetooth on.

## 3. Extract `btsnoop_hci.log`

Option A — bug report (works on every device, no cable tools needed):

```
adb bugreport night.zip
unzip night.zip -d night        # the log is FS/data/misc/bluetooth/logs/btsnoop_hci.log
```

Option B — direct pull (root or debug-friendly builds only):

```
adb root                        # if available
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
```

The file is regenerated per boot; grabbing it the same morning before the next reboot
is safest.

## 4. Import into Sploot

The ingestion API is intentionally UI-free at this stage:

```kotlin
val session = SnoopIngestSession { context.contentResolver.openInputStream(uri) }
val (samples, summary) = session.collect()
// summary: btsnoopRecords, l2capFrames, attNotifications, hr/ibi/acc/ecg sample
// counts, unknownFrames, unknownHandles (handle -> count), batteryPercent.
```

`SnoopIngestSession.run { sample -> ... }` streams samples incrementally for large
files. Unknown frame types and notifications whose attribute handle was never mapped
to a UUID (discovery traffic missing from the capture) are skipped and reported in the
summary counters instead of failing the run.

### What the decoder supports

| Source | Format |
|---|---|
| Polar PMD Data (`FB005C82-…`) — PPI | `[type=3][u64 ns ts][frameType=0][hr u8][pp u16 LE ms][err u16 LE][flags u8]` × N |
| Polar PMD Data — ACC | raw i24 / i16 triples, or delta-compressed frames (16-bit LE refs + 8-bit signed delta blocks), 52 Hz, ±2 g default |
| Polar PMD Data — ECG | i24 LE samples, 130 Hz, µV |
| HR service `0x2A37` | flags byte; HR u8/u16; RR intervals in 1/1024 s → ms (`IbiSource.HR_SERVICE`) |
| Battery `0x2A19` | level reported in `Summary.batteryPercent` |

Handle→UUID mapping is learned from `ATT_READ_BY_TYPE_RESP` / `ATT_FIND_INFORMATION_RESP`
replay, exactly as the live stack discovered it during the night.

### Caveats

- The ACC range (±2/4/8/16 g) is negotiated over the PMD control point and not present
  in notifications; Sploot assumes ±2 g. Override `PmdFrameDecoder(accRangeG = …)` if
  your session used another range (e.g. pass 8 for ±8 g).
- HCI snoop logging must stay enabled for the entire night; toggling it off truncates
  the capture at that point.
- Timestamps: PMD frames carry epoch-nanosecond timestamps and are preferred; records
  without a plausible embedded timestamp fall back to the btsnoop capture clock.

## 5. Sleep staging engine

The bundled ONNX models (`sploot-seq`, `sploot-gbdt`) are **placeholders** that
emit a fixed class prior regardless of input — every real night staged as
constant Light (on-device 2026-11-02). Until trained models ship, the default
engine is `sploot-heuristic` ([HeuristicStagingEngine]): a physiologically-
grounded stager over the same 127-feature per-night-normalized matrix —
vagal tone (rMSSD/SDNN/SD1), mean IBI, LF/HF balance, pNN50, and accelerometer
ENMO when present. A nightly deep-anchor self-calibrates the Deep threshold to
the individual's own vagal range. The Viterbi smoother enforces continuity.
Training real models remains the end goal (`ml_pipeline/train_*.py`).

## 6. Fully automatic morning flow (Phase 12/13 — Shizuku + sensor pull)

With Shizuku set up, the manual steps in sections 3–4 disappear entirely.

### One-time setup

1. Bluetooth HCI snoop logging stays **on** in developer options (already done —
   keep it enabled permanently).
2. Install the **Shizuku** app (Play Store / GitHub) and start it via
   **wireless debugging**: Developer options → Wireless debugging → pair with
   the Shizuku app, then "Start". After the next reboot Shizuku must be
   restarted this way (a 10-second task).
3. Open Shizuku and **grant Sploot** permission (or use Settings → Automatic
   capture → "Grant via Shizuku").
4. Optional sanity check: Settings → Automatic capture → **"Test fetch now"**.
   It reports bytes fetched, snoop records, ATT notifications and the Polar
   sample share (your Garmin watch only inflates log size — the parser filters
   by GATT UUID — but a large non-Polar share means faster log rotation).

### Nightly

Nothing. Sleep with the sensor as usual; Android's stack writes the snoop log.

### Morning

Just **open Sploot**. On app start `MorningCaptureCoordinator` runs, in order:

1. **Sensor pull (primary, Phase 13):** finds the Polar (last-known id, else a
   12 s BLE scan), connects, and downloads every not-yet-imported PPI offline
   recording from the sensor's internal memory via the official SDK
   (`PolarOfflineRecordingPuller`). This needs **no Shizuku and no btsnoop
   log at all** — the sensor is the recorder, so log rotation, cache
   overwrites and Shizuku outages cannot lose the night.
2. **btsnoop fallback:** `SnoopAutoFetcher` acquires the log with shell
   (uid 2000) privileges: (a) direct `cat` over every path in
   `CANDIDATE_PATHS` (AOSP + vendor variants, this device's is
   `/data/log/bt/btsnoop_hci.log`); (b) if permission-blocked, `bugreportz -p`
   streaming only the btsnoop entries out of the produced zip. Both rotation
   generations are fetched when present and concatenated
   (`header + old records + new records`) so a night split by the 65,536-record
   rotation is never lost (on-device failure 2026-11-02: the whole night lived
   in `btsnoop_hci.log.last`).
3. **Cached-capture recovery:** when Shizuku is down but a previous capture is
   cached, and the stored fingerprint predates the current ingest version, the
   cached capture is reprocessed directly — decoding fixes auto-recover
   previously-failed nights.
4. Fingerprint change detection skips unchanged logs; changed logs run through
   `SnoopIngestSession` → `ProcessNightUseCase` and a **morning notification**
   (sleep time, deep/REM minutes, recovery score, koala icon) is posted.

Persist is atomic (`SessionRepository.persistNightAtomic`): session row,
features, stages and intervals commit in one Room transaction, then the
summary is computed inside it — a process death mid-persist leaves nothing
rather than a ghost row with time-but-no-stages. Orphan snoop sessions from
earlier crashes are purged before a retry.

A one-tap **"Capture last night"** button on the Record tab triggers the same
flow manually, and the toggle in Settings disables the automatic run if you
prefer explicit control.

### Polar offline-recording transfers inside the snoop log

When a third-party app (Sleep2, Polar Flow) reads the sensor's offline
recordings over BLE, that transfer transits the captured btsnoop log even
though no live PMD streaming ever happened. `PolarOfflineRecordingDecoder`
recovers it content-based (arming on the `/U/<uid>/<date>/R/<time>/PHYSDATA/
*.REC` write, parsing `[seq u8][6-byte records]` packets, ppi ms at field +4,
anchored on the ASCII UTC start in the metadata packets). This rescued the
2026-11-02 night (8.14 h, 97.8 % valid PPI) after the "no IBI/ACC samples"
failure.

### Implementation notes

- Shell execution is abstracted behind `ShellRunner`; production uses a
  Shizuku UserService (`IShellUserService.exec`) that streams stdout back over a
  `ParcelFileDescriptor` pipe, so multi-hundred-MB logs never sit in a binder
  buffer. Exit codes are not transported — success is inferred from content
  checks (btsnoop magic, `OK:` line, non-empty output).
- The last-imported fingerprint persists in DataStore (`snoop_capture`).
- JVM unit tests (`SnoopAutoFetchTest`) drive the whole fallback chain with a
  scripted fake `ShellRunner`, plus zip-entry extraction from a synthetic
  bugreport zip and fingerprint change/rotation detection.
