#!/usr/bin/env python3
"""Phase 6 placeholder ONNX models for Sploot sleep staging.

Builds two tiny, *valid* ONNX graphs so the full on-device inference pipeline
(features -> PerNightNormalizer -> engine -> ViterbiSmoother) runs end-to-end
before the Phase 7 training pipeline produces real weights.

Both models ignore the input values and emit fixed class-prior rows shaped to
the input's leading dimension:

  models/sploot_seq.onnx : input "features" [1, T, 127] -> "probs" [T, 4]
  models/sploot_gbdt.onnx: input "features" [1, 127]    -> "probs" [1, 4]

Output shape is derived dynamically from the input via Shape/Slice/Concat/Expand
(core ops, opset 17), so any T works. Each model ships with a sidecar metadata
JSON: {"engineId", "featureVersion", "logits"}.

Real trained weights (Phase 7, ml_pipeline/train_*.py) will replace these files.

Usage:
    python3 ml_pipeline/placeholder_models.py [output_dir]
Output dir defaults to app/src/main/assets/models.
"""
import json
import pathlib
import sys

import onnx
from onnx import TensorProto, helper, numpy_helper

OPSET = 17
IR_VERSION = 9
NUM_FEATURES = 127
NUM_CLASSES = 4
# Fixed softmax-of-priors placeholder output: wake/light/deep/rem
PRIORS = [0.10, 0.45, 0.25, 0.20]


def build_model(name: str, input_shape, output_shape, leading_dim_axis: int) -> onnx.ModelProto:
    """Constant-prior model whose output rows track the input's batch/time dim."""
    x = helper.make_tensor_value_info("features", TensorProto.FLOAT, input_shape)
    y = helper.make_tensor_value_info("probs", TensorProto.FLOAT, output_shape)

    priors = numpy_helper.from_array(
        __import__("numpy").array(PRIORS, dtype="float32").reshape(1, NUM_CLASSES), "priors")
    four = numpy_helper.from_array(
        __import__("numpy").array([NUM_CLASSES], dtype="int64"), "four")
    starts = numpy_helper.from_array(
        __import__("numpy").array([leading_dim_axis], dtype="int64"), "starts")
    ends = numpy_helper.from_array(
        __import__("numpy").array([leading_dim_axis + 1], dtype="int64"), "ends")
    axes = numpy_helper.from_array(
        __import__("numpy").array([0], dtype="int64"), "axes")

    nodes = [
        helper.make_node("Shape", ["features"], ["shape"]),
        helper.make_node("Slice", ["shape", "starts", "ends", "axes"], ["tdim"]),
        helper.make_node("Concat", ["tdim", "four"], ["target"], axis=0),
        helper.make_node("Expand", ["priors", "target"], ["probs"]),
    ]
    graph = helper.make_graph(
        nodes, name, [x], [y],
        initializer=[priors, four, starts, ends, axes])
    model = helper.make_model(
        graph, producer_name="sploot-placeholder",
        opset_imports=[helper.make_opsetid("", OPSET)])
    model.ir_version = IR_VERSION
    onnx.checker.check_model(model)
    return model


def main() -> None:
    out_dir = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else
                           "app/src/main/assets/models")
    out_dir.mkdir(parents=True, exist_ok=True)

    # Engine ids match app/src/main/java/com/example/sploot/ml/SleepStagingEngine.kt
    # (EngineRegistry.SEQ / EngineRegistry.GBDT); the loader expects
    # <engineId>.onnx + <engineId>.json side by side.
    models = {
        "sploot-seq": ([1, "T", NUM_FEATURES], ["T", NUM_CLASSES]),
        "sploot-gbdt": ([1, NUM_FEATURES], [1, NUM_CLASSES]),
    }
    for engine_id, (in_shape, out_shape) in models.items():
        model = build_model(engine_id, in_shape, out_shape, leading_dim_axis=0 if engine_id.endswith("gbdt") else 1)
        onnx_path = out_dir / f"{engine_id}.onnx"
        onnx.save(model, onnx_path)
        meta = {
            "engineId": engine_id,
            "featureVersion": 1,
            "logits": False,
            "inputName": "features",
            "outputName": "probs",
            "inputShape": in_shape,
            "outputClasses": ["wake", "light", "deep", "rem"],
            "placeholder": True,
        }
        (out_dir / f"{engine_id}.json").write_text(json.dumps(meta, indent=2) + "\n")
        print(f"wrote {onnx_path} ({onnx_path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
