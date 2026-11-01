#!/usr/bin/env python3
"""Train the primary sequence sleep-staging model and export it to ONNX.

Architecture (whole-night seq2seq, variable T):
  input [B, T, 127]
    -> Conv1d(127->64, k=5, pad=2) -> GELU
    -> Conv1d(64->64, k=5, dil=2, pad=4) -> GELU
    -> BiGRU(64, hidden=64, 1 layer)
    -> temporal dot-product attention pooling context vector per timestep
    -> Linear(128 -> 4), softmax
  output [B, T, 4] probabilities; exported with a squeezed batch dim -> [T, 4].

Contract (must match ModelLoader.kt / OnnxSequenceEngine.kt):
  input  "features" float32 [1, T, 127]  (dynamic T)
  output "probs"    float32 [T, 4] probabilities (softmax embedded, logits:false)

NaN policy: features are NaN-imputed to 0.0 before the model — same policy the
on-device PerNightNormalizer applies; validity is implicitly encoded by the
windowed NaN structure itself (epochs with no clean data have all-HRV-NaN and
clock features only). Extra validity channels are NOT used because the ONNX
input contract is fixed at 127 channels.

Class imbalance: weighted CrossEntropy with inverse-frequency weights.
Split: by subject (group split), never by epoch.

Usage:
  python ml_pipeline/train_sequence_model.py --data ml_pipeline/data/shhs1 \
      --out ml_pipeline/artifacts
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import features as F  # noqa: E402

import pandas as pd  # noqa: E402
import torch  # noqa: E402
import torch.nn as nn  # noqa: E402

STAGES = ["wake", "light", "deep", "rem"]
ENGINE_ID = "sploot-seq"


class SeqSleepNet(nn.Module):
    def __init__(self, n_in: int = 127, n_classes: int = 4, hidden: int = 64):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv1d(n_in, hidden, 5, padding=2), nn.GELU(),
            nn.Conv1d(hidden, hidden, 5, dilation=2, padding=4), nn.GELU(),
        )
        self.gru = nn.GRU(hidden, hidden, batch_first=True, bidirectional=True)
        self.attn_v = nn.Linear(2 * hidden, 1, bias=False)
        self.out = nn.Linear(4 * hidden, n_classes)

    def forward(self, x):  # x: [B, T, F]
        h = self.conv(x.transpose(1, 2)).transpose(1, 2)      # [B, T, H]
        h, _ = self.gru(h)                                     # [B, T, 2H]
        # temporal attention: per-step context over the whole sequence
        scores = self.attn_v(torch.tanh(h)).squeeze(-1)        # [B, T]
        w = torch.softmax(scores, dim=-1)                      # [B, T]
        ctx = torch.einsum("bt,bth->bh", w, h)                 # [B, 2H]
        ctx = ctx.unsqueeze(1).expand(-1, h.size(1), -1)       # [B, T, 2H]
        logits = self.out(torch.cat([h, ctx], dim=-1))         # [B, T, C]
        return torch.softmax(logits, dim=-1)

    def forward_export(self, x):
        # batch-squeezing wrapper: [1, T, F] -> [T, C]
        return self.forward(x).squeeze(0)


def load_sequences(data_dir: pathlib.Path):
    """Group per-night feature matrices into sequences keyed by subject."""
    shards = sorted(data_dir.glob("*.parquet"))
    assert shards, f"no parquet shards in {data_dir}"
    seqs = []
    for shard in shards:
        df = pd.read_parquet(shard)
        if "label" not in df:
            continue
        df = df[df["label"] >= 0]
        if len(df) < 20:
            continue
        x = df[F.FEATURE_NAMES].to_numpy(dtype=np.float32)
        x = np.nan_to_num(x, nan=0.0, posinf=0.0, neginf=0.0)
        y = df["label"].to_numpy(dtype=np.int64)
        seqs.append((shard.stem, x, y))
    return seqs


def pad_batch(batch):
    maxlen = max(x.shape[0] for _, x, _ in batch)
    xs = np.zeros((len(batch), maxlen, len(F.FEATURE_NAMES)), dtype=np.float32)
    ys = np.full((len(batch), maxlen), -100, dtype=np.int64)  # ignore_index
    mask = np.zeros((len(batch), maxlen), dtype=bool)
    for i, (_, x, y) in enumerate(batch):
        xs[i, : x.shape[0]] = x
        ys[i, : y.shape[0]] = y
        mask[i, : x.shape[0]] = True
    return (torch.from_numpy(xs), torch.from_numpy(ys), torch.from_numpy(mask))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="ml_pipeline/data/shhs1")
    ap.add_argument("--out", default="ml_pipeline/artifacts")
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--batch-nights", type=int, default=8)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--val-frac", type=float, default=0.15)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    F.validate_feature_names()
    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)

    seqs = load_sequences(pathlib.Path(args.data))
    subjects = sorted({s for s, _, _ in seqs})
    print(f"{len(seqs)} nights / {len(subjects)} subjects")

    # Subject-level split
    rng.shuffle(subjects)
    n_val = max(1, int(len(subjects) * args.val_frac))
    val_subjects = set(subjects[:n_val])
    train = [s for s in seqs if s[0] not in val_subjects]
    val = [s for s in seqs if s[0] in val_subjects] or train

    # Class weights from training labels
    counts = np.bincount(np.concatenate([y for _, _, y in train]), minlength=4)
    weights = torch.tensor(counts.sum() / np.maximum(counts, 1) / 4.0, dtype=torch.float32)
    print("class counts:", counts.tolist(), "weights:", weights.tolist())

    model = SeqSleepNet()
    opt = torch.optim.Adam(model.parameters(), lr=args.lr)
    lossf = nn.CrossEntropyLoss(weight=weights, ignore_index=-100)

    def run_epoch(data, train_mode=True):
        model.train(train_mode)
        total, n = 0.0, 0
        order = rng.permutation(len(data))
        for b0 in range(0, len(data), args.batch_nights):
            batch = [data[i] for i in order[b0: b0 + args.batch_nights]]
            xs, ys, mask = pad_batch(batch)
            with torch.set_grad_enabled(train_mode):
                probs = model(xs)
                loss = lossf(probs.reshape(-1, 4), ys.reshape(-1))
            if train_mode:
                opt.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
                opt.step()
            total += float(loss) * len(batch)
            n += len(batch)
        return total / max(n, 1)

    for ep in range(1, args.epochs + 1):
        tr = run_epoch(train, True)
        va = run_epoch(val, False)
        print(f"epoch {ep}: train loss {tr:.4f}  val loss {va:.4f}")

    # ---------------- export ----------------
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model.eval()

    class _ExportWrapper(nn.Module):
        """[1, T, F] -> [T, C] to satisfy the on-device output contract."""

        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, x):
            return self.inner(x).squeeze(0)

    wrapper = _ExportWrapper(model).eval()
    dummy = torch.zeros(1, 5, len(F.FEATURE_NAMES), dtype=torch.float32)
    torch.onnx.export(
        wrapper, dummy, str(out / f"{ENGINE_ID}.onnx"),
        input_names=["features"], output_names=["probs"],
        dynamic_axes={"features": {1: "T"}, "probs": {0: "T"}},
        opset_version=17,
        dynamo=False,  # legacy exporter; avoids the onnxscript dependency
    )
    meta = {
        "engineId": ENGINE_ID,
        "featureVersion": F.FEATURE_VERSION,
        "logits": False,
        "inputName": "features",
        "outputName": "probs",
        "inputShape": [1, "T", 127],
        "outputClasses": STAGES,
        "placeholder": False,
        "trainedOn": f"{len(train)} nights / {len(subjects) - n_val} subjects",
    }
    (out / f"{ENGINE_ID}.json").write_text(json.dumps(meta, indent=2) + "\n")

    # smoke-check with onnxruntime
    import onnxruntime as ort
    sess = ort.InferenceSession(str(out / f"{ENGINE_ID}.onnx"),
                                providers=["CPUExecutionProvider"])
    for t in (5, 37, 120):
        x = rng.standard_normal((1, t, 127)).astype(np.float32)
        p = sess.run(["probs"], {"features": x})[0]
        assert p.shape == (t, 4), f"output shape {p.shape} != ({t},4)"
        assert np.allclose(p.sum(axis=1), 1.0, atol=1e-3), "rows not normalised"
    print(f"exported {out / (ENGINE_ID + '.onnx')} — shape/softmax contract OK")


if __name__ == "__main__":
    main()
