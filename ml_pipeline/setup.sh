#!/usr/bin/env bash
# Sploot ml_pipeline environment setup.
# Creates ml_pipeline/.venv and installs the pinned dependency set.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
echo "Using $PYTHON ($($PYTHON --version))"

"$PYTHON" -m venv .venv
# shellcheck disable=SC1091
source .venv/bin/activate
pip install --upgrade pip wheel

# CPU-only torch to keep the env small; drop the index-url for CUDA builds.
pip install torch --index-url https://download.pytorch.org/whl/cpu

pip install -r requirements.txt

python - <<'EOF'
import numpy, pandas, scipy, onnx, onnxruntime, lightgbm, sklearn
import torch
print("numpy       ", numpy.__version__)
print("onnx        ", onnx.__version__)
print("onnxruntime ", onnxruntime.__version__)
print("torch       ", torch.__version__)
print("lightgbm    ", lightgbm.__version__)
print("OK")
EOF

echo
echo "Toolchain OK. Activate with:  source ml_pipeline/.venv/bin/activate"
