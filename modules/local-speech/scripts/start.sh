#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
service_root="$PWD"
mkdir -p runtime/tmp
chmod +x scripts/run.sh
cat > runtime/memomeet-gpu-speech.service <<EOF
[Unit]
Description=MemoMeet standalone GPU speech inference
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$service_root
ExecStart=$service_root/scripts/run.sh
Environment=HF_TOKEN_FILE=%h/.cache/huggingface/token
Environment=WHISPER_BATCH_SIZE=1
Environment=TMPDIR=$service_root/runtime/tmp
Environment=PYTHONUNBUFFERED=1
Restart=on-failure
RestartSec=10
TimeoutStopSec=30
StandardOutput=append:$service_root/runtime/service.log
StandardError=append:$service_root/runtime/service.log

[Install]
WantedBy=default.target
EOF
systemctl --user link "$service_root/runtime/memomeet-gpu-speech.service"
systemctl --user daemon-reload
systemctl --user enable memomeet-gpu-speech.service
systemctl --user start memomeet-gpu-speech.service
systemctl --user status memomeet-gpu-speech.service --no-pager
