#!/usr/bin/env bash
# Assert the toolchain actually generated something real. Checking only that the
# build succeeded would pass on an empty file.
set -euo pipefail
OUT="${1:?generated .clj}"
[ -f "$OUT" ] || {
  echo "FAIL: $OUT missing" >&2
  exit 1
}
grep -q '^(ns example.hello' "$OUT" || {
  echo "FAIL: no ns form in $OUT" >&2
  exit 1
}
grep -q 'defrecord Hello' "$OUT" || {
  echo "FAIL: no Hello record" >&2
  exit 1
}
grep -q 'Greeter' "$OUT" || {
  echo "FAIL: no Greeter service" >&2
  exit 1
}
echo "OK: $(wc -l <"$OUT" | tr -d ' ') lines generated via the prebuilt plugin"
