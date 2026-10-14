#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
source "$root/scripts/toolchain.sh"
cd "$root"
if [[ -f .env ]]; then set -a; source .env; set +a; fi
# An empty LLM key uses the configured DashScope key.
if [[ -z "${LLM_API_KEY:-}" ]]; then export LLM_API_KEY="${DASHSCOPE_API_KEY:-}"; fi
mkdir -p "${DATA_DIR:-./data}"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$java_bin" ${JAVA_OPTS:--Xms64m -Xmx384m} -jar backend/target/memo-meeting-0.1.0.jar
