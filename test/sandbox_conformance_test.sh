#!/usr/bin/env bash
# The BSR runs remote plugins in a sandbox and requires them to be a pure
# function of the CodeGeneratorRequest: no filesystem, no network, no
# environment. Anything else is not a conforming Protobuf plugin as far as buf is
# concerned, and would disqualify us from remote execution.
#
# System/in and System/out are the protocol itself, so they are not banned.
set -euo pipefail

SRC="${1:?path to plugin.clj}"
[ -f "$SRC" ] || {
  echo "FAIL: $SRC not found" >&2
  exit 1
}

BANNED=(
  'slurp' 'spit' 'io/file' 'clojure.java.io'
  'getenv' 'getProperty' 'ProcessBuilder'
  'java.net.' 'Socket.' 'URL.' 'FileInputStream' 'FileOutputStream'
)

status=0
for pattern in "${BANNED[@]}"; do
  if grep -Fn -- "$pattern" "$SRC" >/dev/null 2>&1; then
    echo "FAIL: plugin.clj references '$pattern', which the BSR sandbox forbids:" >&2
    grep -Fn -- "$pattern" "$SRC" >&2
    status=1
  fi
done

# Assert the file was actually inspected — an empty or truncated source would
# otherwise pass every check above by containing nothing at all.
lines=$(wc -l <"$SRC" | tr -d ' ')
[ "$lines" -gt 100 ] || {
  echo "FAIL: $SRC has only $lines lines; wrong file?" >&2
  status=1
}

[ "$status" = 0 ] && echo "OK: pure function of the request ($lines lines checked)"
exit "$status"
