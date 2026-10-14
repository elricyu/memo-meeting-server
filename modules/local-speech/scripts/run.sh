#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then set -a; source .env; set +a; fi
export HF_HOME="$PWD/models/huggingface"
export TORCH_HOME="$PWD/models/torch"
export PYANNOTE_METRICS_ENABLED=0
export HF_HUB_DISABLE_XET=1
export OMP_NUM_THREADS=4
export MKL_NUM_THREADS=4
export TOKENIZERS_PARALLELISM=false
export PATH="$PWD/runtime/ffmpeg-root/usr/bin:$PATH"
export LD_LIBRARY_PATH="$PWD/runtime/ffmpeg-root/usr/lib/x86_64-linux-gnu:$(.venv/bin/python -c 'import pathlib; p=pathlib.Path(".venv/lib"); print(":".join(str(x.resolve()) for x in p.glob("python*/site-packages/nvidia/*/lib")))'):${LD_LIBRARY_PATH:-}"
exec .venv/bin/python -m uvicorn service:app --host 127.0.0.1 --port 18092 --workers 1
