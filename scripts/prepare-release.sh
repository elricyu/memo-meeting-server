#!/usr/bin/env bash
# Linux host deployment: verify and atomically publish an immutable JAR.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
source_jar="$root/backend/target/memo-meeting-0.1.0.jar"
directory="$root/releases"
for tool in unzip sha256sum sync; do
  command -v "$tool" >/dev/null || { echo "缺少发布工具：$tool" >&2; exit 1; }
done
mkdir -p "$directory"
work="$(mktemp -d "$directory/.prepare.XXXXXX")"
trap 'rm -rf "$work"' EXIT
# Validate the snapshot that will actually be published, not a changing build file.
cp "$source_jar" "$work/app.jar"
unzip -tq "$work/app.jar" >/dev/null || { echo "构建产物损坏" >&2; exit 1; }
unzip -Z1 "$work/app.jar" >"$work/entries"
grep -Fxq 'BOOT-INF/classes/static/index.html' "$work/entries" || { echo "构建产物缺少 Vue 页面" >&2; exit 1; }
read -r digest _ < <(sha256sum "$work/app.jar")
target="$directory/$digest.jar"
if [[ -e "$target" ]]; then
  read -r existing_digest _ < <(sha256sum "$target")
  [[ "$existing_digest" == "$digest" ]] || { echo "已有发布副本校验失败" >&2; exit 1; }
else
  chmod 444 "$work/app.jar"
  sync -f "$work/app.jar"
  mv "$work/app.jar" "$target"
fi
ln -s "$digest.jar" "$work/current.jar"
mv -Tf "$work/current.jar" "$directory/current.jar"
sync -f "$directory"
printf '发布 JAR：%s\n' "$target"
