#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
test_root="$(mktemp -d /tmp/memo-meeting-diarization.XXXXXX)"
port="${DIARIZATION_TEST_PORT:-19094}"
mock_port="${DIARIZATION_MOCK_PORT:-19104}"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
app_pid="";mock_pid=""
cleanup(){
  if [[ -n "$app_pid" ]];then kill "$app_pid" 2>/dev/null || true;wait "$app_pid" 2>/dev/null || true;fi
  if [[ -n "$mock_pid" ]];then kill "$mock_pid" 2>/dev/null || true;wait "$mock_pid" 2>/dev/null || true;fi
}
trap cleanup EXIT
MOCK_PORT="$mock_port" node scripts/mock-cloud.mjs >"$test_root/mock.log" 2>&1 &
mock_pid="$!"
PORT="$port" DATA_DIR="$test_root/data" BIND_ADDRESS=127.0.0.1 ASR_PROVIDER="${DIARIZATION_PROVIDER:-DEEPGRAM}" ASR_MODEL="${DIARIZATION_MODEL:-nova-3}" \
DASHSCOPE_API_KEY="${DIARIZATION_TEST_KEY:-explicit-protocol-test-key}" ASR_WS_URL="ws://127.0.0.1:$mock_port/${DIARIZATION_PATH:-deepgram}" \
LLM_API_KEY=explicit-protocol-test-key LLM_URL="http://127.0.0.1:$mock_port/chat" \
ALLOWED_ORIGINS="http://127.0.0.1:$port" \
"$java_bin" -Xms64m -Xmx256m -jar backend/target/memo-meeting-0.1.0.jar >"$test_root/server.log" 2>&1 &
app_pid="$!"
ready=0
for _ in {1..100};do
  if ! kill -0 "$app_pid" 2>/dev/null || ! kill -0 "$mock_pid" 2>/dev/null;then cat "$test_root/server.log" "$test_root/mock.log";exit 1;fi
  if curl -fsS "http://127.0.0.1:$port/api/v1/health" >/dev/null 2>&1;then ready=1;break;fi
  sleep .2
done
if [[ "$ready" != 1 ]];then cat "$test_root/server.log";exit 1;fi
TEST_PROVIDER="${DIARIZATION_PROVIDER:-DEEPGRAM}" TEST_URL="http://127.0.0.1:$port" node scripts/diarization-integration.mjs | tee "$test_root/result.log"
if rg 'ERROR|Unhandled exception|HttpMessageNotWritableException' "$test_root/server.log";then exit 1;fi
printf '分人协议测试证据：%s\n' "$test_root"
