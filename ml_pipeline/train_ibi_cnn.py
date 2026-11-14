#!/usr/bin/env python3
"""Train a sleep² style raw-IBI network (multi-resolution CNN -> BiGRU) on HMC PSG nights.

Input  : [B, 2, T*60]  2 Hz z-scored interpolated IBI + validity mask  (see ibi_seq.py)
Output : [B, T, 4]     Wake / Light / Deep / REM probabilities per 30 s epoch

Training tricks taken from Topalidis et al. 2023:
  * whole-night context (BiGRU over epochs), multi-resolution kernels
  * class-weighted cross-entropy to avoid the "everything is Light" bias
  * model selection on validation *loss* (not accuracy)
Extra for PPG transfer (the app uses Verity Sense PPI, the training data is ECG RR):
  * random IBI jitter, random signal dropouts, random crops.

Usage: python ml_pipeline/train_ibi_cnn.py --data .tmp/hmc --out ml_pipeline/artifacts
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as Fn

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ibi_seq as S  # noqa: E402

STAGES = ["wake", "light", "deep", "rem"]
ENGINE_ID = "sploot-ibi-cnn"


class IbiNet(nn.Module):
    def __init__(self, hidden: int = 96, gru_hidden: int = 96, drop: float = 0.25):
        super().__init__()
        # multi-resolution front end (~ 2 s, 10 s, 30 s receptive fields at 2 Hz)
        ks = (5, 21, 61)
        self.branches = nn.ModuleList(
            [nn.Conv1d(2, 24, k, padding=k // 2) for k in ks])
        c1 = 24 * len(ks)
        self.bn1 = nn.BatchNorm1d(c1)
        self.pool1 = nn.AvgPool1d(5)              # 2 Hz -> 0.4 Hz
        self.conv2 = nn.Conv1d(c1, hidden, 7, padding=3)
        self.bn2 = nn.BatchNorm1d(hidden)
        self.pool2 = nn.AvgPool1d(3)              # -> 0.133 Hz
        self.conv3 = nn.Conv1d(hidden, hidden, 5, padding=2)
        self.bn3 = nn.BatchNorm1d(hidden)
        self.pool3 = nn.AvgPool1d(4)              # -> 1 step / 30 s epoch
        self.drop = nn.Dropout(drop)
        self.gru = nn.GRU(hidden, gru_hidden, num_layers=2, batch_first=True,
                          bidirectional=True, dropout=drop)
        self.out = nn.Linear(2 * gru_hidden, 4)

    def logits(self, x):                           # x: [B, 2, N]
        h = torch.cat([b(x) for b in self.branches], dim=1)
        h = self.pool1(Fn.gelu(self.bn1(h)))
        h = self.pool2(Fn.gelu(self.bn2(self.conv2(h))))
        h = self.pool3(Fn.gelu(self.bn3(self.conv3(h))))   # [B, H, T]
        h = self.drop(h.transpose(1, 2))
        h, _ = self.gru(h)
        return self.out(self.drop(h))              # [B, T, 4]

    def forward(self, x):
        return torch.softmax(self.logits(x), dim=-1)


# ----------------------------------------------------------------------------- data

def load_nights(d: pathlib.Path):
    nights = []
    for f in sorted(d.glob("*.npz")):
        z = np.load(f)
        y = z["labels"].astype(np.int64)
        if (y >= 0).sum() < 200:
            continue
        nights.append((f.stem, z["t_ms"].astype(np.float64), z["ibi_ms"].astype(np.float64), y))
    return nights


def make_input(t, ibi, n_epochs, augment, rng):
    ibi = ibi.copy()
    if augment:
        ibi = ibi + rng.normal(0, rng.uniform(0, 12), ibi.shape)       # PPG-like jitter
        keep = np.ones(ibi.size, bool)
        for _ in range(rng.integers(0, 4)):                              # signal dropouts
            c = rng.uniform(0, t[-1])
            w = rng.uniform(30_000, 600_000)
            keep &= ~((t > c) & (t < c + w))
        t, ibi = t[keep], ibi[keep]
    return S.to_sequence(t, ibi, 0.0, n_epochs)


def batchify(items):
    T = max(x.shape[1] // S.SAMPLES_PER_EPOCH for x, _ in items)
    xs = np.zeros((len(items), 2, T * S.SAMPLES_PER_EPOCH), np.float32)
    ys = np.full((len(items), T), -100, np.int64)
    for i, (x, y) in enumerate(items):
        xs[i, :, : x.shape[1]] = x
        ys[i, : y.size] = np.where(y < 0, -100, y)   # unscored epochs -> ignored by the loss
    return torch.from_numpy(xs), torch.from_numpy(ys)


def kappa_acc(y, p):
    from sklearn.metrics import cohen_kappa_score
    return float((y == p).mean()), float(cohen_kappa_score(y, p))


@torch.no_grad()
def evaluate(model, nights, rng):
    model.eval()
    ys, ps, loss_sum, n = [], [], 0.0, 0
    per_night = []
    for _, t, ibi, y in nights:
        x = torch.from_numpy(make_input(t, ibi, y.size, False, rng))[None]
        lg = model.logits(x)[0]
        yt = torch.from_numpy(y)
        loss_sum += float(Fn.cross_entropy(lg, yt, ignore_index=-1, reduction="sum"))
        m = y >= 0
        n += int(m.sum())
        p = lg.argmax(-1).numpy()
        ys.append(y[m]); ps.append(p[m])
        per_night.append(kappa_acc(y[m], p[m]))
    y, p = np.concatenate(ys), np.concatenate(ps)
    acc, kap = kappa_acc(y, p)
    return loss_sum / max(n, 1), acc, kap, y, p, per_night


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=".tmp/hmc")
    ap.add_argument("--out", default="ml_pipeline/artifacts")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--crop", type=int, default=720, help="train crop length in epochs")
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--weight-power", type=float, default=0.5,
                    help="class weight = (1/freq)^power (1 = full inverse frequency)")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    nights = load_nights(pathlib.Path(args.data))
    idx = rng.permutation(len(nights))
    n_val = max(3, int(0.12 * len(nights)))
    n_test = max(3, int(0.12 * len(nights)))
    test = [nights[i] for i in idx[:n_test]]
    val = [nights[i] for i in idx[n_test:n_test + n_val]]
    train = [nights[i] for i in idx[n_test + n_val:]]
    print(f"nights: train {len(train)} val {len(val)} test {len(test)}", flush=True)

    counts = np.bincount(np.concatenate([y[y >= 0] for *_, y in train]), minlength=4)
    w = (counts.sum() / counts / 4.0) ** args.weight_power
    print("class freq", (counts / counts.sum()).round(3), "weights", w.round(2), flush=True)
    weights = torch.tensor(w, dtype=torch.float32)

    model = IbiNet()
    print("params", sum(p.numel() for p in model.parameters()), flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-2)
    steps = args.epochs * ((len(train) + args.batch - 1) // args.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=steps)

    best, out = (1e9, None), pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    for ep in range(1, args.epochs + 1):
        model.train()
        order = rng.permutation(len(train))
        tl = 0.0
        for b0 in range(0, len(order), args.batch):
            items = []
            for i in order[b0:b0 + args.batch]:
                _, t, ibi, y = train[i]
                n = y.size
                if n > args.crop:
                    s = int(rng.integers(0, n - args.crop + 1))
                    sel = (t >= s * 30_000) & (t < (s + args.crop) * 30_000)
                    x = make_input(t[sel] - s * 30_000, ibi[sel], args.crop, True, rng)
                    y = y[s:s + args.crop]
                else:
                    x = make_input(t, ibi, n, True, rng)
                items.append((x, y))
            xs, ys = batchify(items)
            lg = model.logits(xs)
            loss = Fn.cross_entropy(lg.reshape(-1, 4), ys.reshape(-1),
                                    weight=weights, ignore_index=-100)
            opt.zero_grad(); loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 2.0)
            opt.step(); sched.step()
            tl += float(loss) * len(items)
        vl, va, vk, *_ = evaluate(model, val, rng)
        mark = ""
        if vl < best[0]:
            best = (vl, {k: v.clone() for k, v in model.state_dict().items()})
            mark = " *"
        print(f"ep {ep:02d} train {tl/len(train):.4f} | val loss {vl:.4f} acc {va:.3f} "
              f"kappa {vk:.3f}{mark}  [{time.time()-t0:.0f}s]", flush=True)

    model.load_state_dict(best[1])
    torch.save(best[1], out / f"{ENGINE_ID}.pt")
    _, acc, kap, y, p, per_night = evaluate(model, test, rng)
    from sklearn.metrics import confusion_matrix, f1_score
    print(f"\nTEST ({len(test)} held-out nights): acc {acc:.3f} kappa {kap:.3f}")
    print("per-night kappa: mean %.3f  min %.3f" % (np.mean([k for _, k in per_night]),
                                                    np.min([k for _, k in per_night])))
    print("F1 per class", dict(zip(STAGES, f1_score(y, p, average=None).round(3))))
    print("confusion (rows=true)\n", confusion_matrix(y, p))

    # ---------------- ONNX export ----------------
    model.eval()

    class Wrap(nn.Module):
        def __init__(self, m):
            super().__init__(); self.m = m

        def forward(self, x):          # [1, 2, N] -> [T, 4]
            return self.m(x).squeeze(0)

    dummy = torch.zeros(1, 2, 60 * 40)
    torch.onnx.export(Wrap(model).eval(), dummy, str(out / f"{ENGINE_ID}.onnx"),
                      input_names=["ibi"], output_names=["probs"],
                      dynamic_axes={"ibi": {2: "N"}, "probs": {0: "T"}},
                      opset_version=17, dynamo=False)
    meta = {"engineId": ENGINE_ID, "inputName": "ibi", "outputName": "probs",
            "fs": S.FS, "samplesPerEpoch": S.SAMPLES_PER_EPOCH, "classes": STAGES,
            "testKappa": kap, "testAccuracy": acc, "trainedOn": f"HMC {len(train)} nights",
            "preprocess": {"minIbi": S.MIN_IBI, "maxIbi": S.MAX_IBI, "gapSeconds": S.GAP_S,
                           "ectopicDeviation": 0.2, "medianWindow": 5}}
    (out / f"{ENGINE_ID}.json").write_text(json.dumps(meta, indent=2) + "\n")

    import onnxruntime as ort
    sess = ort.InferenceSession(str(out / f"{ENGINE_ID}.onnx"), providers=["CPUExecutionProvider"])
    _, t, ibi, y = test[0]
    x = make_input(t, ibi, y.size, False, rng)[None]
    onnx_p = sess.run(["probs"], {"ibi": x})[0]
    torch_p = model(torch.from_numpy(x))[0].detach().numpy()
    print("onnx vs torch max diff", float(np.abs(onnx_p - torch_p).max()), "shape", onnx_p.shape)


if __name__ == "__main__":
    main()
