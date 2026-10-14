#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
# The checked-in OpenAPI JSON is the maintained contract.
node -e 'JSON.parse(require("node:fs").readFileSync("docs/api/openapi.json", "utf8"))'
cd frontend
npm ci
npm test
npm run build
mkdir -p "$root/backend/src/main/resources/static/docs"
cp -R dist/. "$root/backend/src/main/resources/static/"
cp "$root/docs/api/openapi.json" "$root/backend/src/main/resources/static/docs/openapi.json"
cp "$root/docs/api/protocol-v1.md" "$root/backend/src/main/resources/static/docs/protocol-v1.md"
cp "$root/docs/api/device-binding-v1.md" "$root/backend/src/main/resources/static/docs/device-binding-v1.md"
cd "$root/backend"
"${MVN:-mvn}" -B -ntp test package
