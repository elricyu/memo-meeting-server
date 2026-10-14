#!/usr/bin/env bash
# Respect an explicit Java installation; otherwise use tools from PATH.
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
