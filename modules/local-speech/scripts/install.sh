#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p runtime/bin runtime/cache
export UV_CACHE_DIR="$PWD/runtime/cache/uv"
if [[ ! -x runtime/bin/uv ]]; then
  curl -LsSf https://astral.sh/uv/install.sh -o runtime/install-uv.sh
  UV_INSTALL_DIR="$PWD/runtime/bin" UV_NO_MODIFY_PATH=1 sh runtime/install-uv.sh
fi
if [[ ! -x .venv/bin/python ]]; then runtime/bin/uv venv --python python3 .venv; fi
runtime/bin/uv pip install --python .venv/bin/python torch==2.8.0 torchaudio==2.8.0 torchvision==0.23.0 --index-url https://download.pytorch.org/whl/cu126
runtime/bin/uv pip install --python .venv/bin/python -r requirements.txt
runtime/bin/uv pip freeze --python .venv/bin/python > requirements.lock.txt
