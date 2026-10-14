#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
app_port="${TEST_PORT:-19092}"
mock_port="${MOCK_PORT:-19102}"
test_root="$(mktemp -d /tmp/memo-meeting-integration.XXXXXX)"
app_pid=""
mock_pid=""
cleanup() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  if [[ -n "$mock_pid" ]]; then kill "$mock_pid" 2>/dev/null || true; wait "$mock_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT
MOCK_PORT="$mock_port" node scripts/mock-cloud.mjs >"$test_root/mock.log" 2>&1 &
mock_pid="$!"
PORT="$app_port" DATA_DIR="$test_root/data" BIND_ADDRESS=127.0.0.1 \
DASHSCOPE_API_KEY=explicit-protocol-test-key ASR_WS_URL="ws://127.0.0.1:$mock_port/asr" \
LLM_API_KEY=explicit-protocol-test-key LLM_URL="http://127.0.0.1:$mock_port/chat" \
ALLOWED_ORIGINS="http://127.0.0.1:$app_port" \
"$java_bin" -Xms64m -Xmx256m -jar backend/target/memo-meeting-0.1.0.jar >"$test_root/server.log" 2>&1 &
app_pid="$!"
ready=0
for _ in {1..100}; do
  if ! kill -0 "$app_pid" 2>/dev/null || ! kill -0 "$mock_pid" 2>/dev/null; then cat "$test_root/server.log" "$test_root/mock.log"; exit 1; fi
  if curl -fsS "http://127.0.0.1:$app_port/api/v1/health" >/dev/null 2>&1; then ready=1; break; fi
  sleep 0.2
done
if [[ "$ready" != 1 ]]; then cat "$test_root/server.log"; exit 1; fi
TEST_URL="http://127.0.0.1:$app_port" node scripts/integration.mjs | tee "$test_root/result.log"
TEST_URL="http://127.0.0.1:$app_port" node scripts/code-binding-integration.mjs | tee -a "$test_root/result.log"
TEST_URL="http://127.0.0.1:$app_port" node scripts/device-integration.mjs | tee -a "$test_root/result.log"
TEST_URL="http://127.0.0.1:$((app_port + 1))" RESTART_DATA_DIR="$test_root/restart-data" \
DASHSCOPE_API_KEY=explicit-protocol-test-key ASR_WS_URL="ws://127.0.0.1:$mock_port/asr" \
LLM_API_KEY=explicit-protocol-test-key LLM_URL="http://127.0.0.1:$mock_port/chat" \
node scripts/restart-test.mjs | tee -a "$test_root/result.log"
TEST_URL="http://127.0.0.1:$((app_port + 2))" ADMIN_TEST_DATA_DIR="$test_root/admin-data" \
DASHSCOPE_API_KEY=explicit-protocol-test-key ASR_WS_URL="ws://127.0.0.1:$mock_port/asr" \
LLM_API_KEY=explicit-protocol-test-key LLM_URL="http://127.0.0.1:$mock_port/chat" \
node scripts/admin-integration.mjs | tee -a "$test_root/result.log"
# SSE disconnects are expected, but must not trigger a JSON conversion error.
if grep -E 'Unhandled exception|HttpMessageNotWritableException|ERROR' "$test_root/server.log"; then exit 1; fi
printf '测试证据保留在 %s\n' "$test_root"
