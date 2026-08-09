#!/usr/bin/env bash
# The test that matters for a distributed plugin: drive the native binary with
# real protoc and real buf, from a scratch directory that has no Bazel, no
# Clojure and no JVM on the path for the plugin's benefit.
#
# Everything in `bazel test //...` runs inside Bazel with the JVM plugin, so none
# of it exercises the artifact users actually download.
#
# Usage:
#   bazel test //test:distribution_test          the binary this repo builds
#   PLUGIN=<path> bazel run //test:distribution_check
#                                               a downloaded release asset
#
# Args are <protoc> <buf> [plugin]; PLUGIN in the environment wins. protoc and buf
# are hermetic runfiles in both cases, so this never depends on what is installed.
set -euo pipefail

abspath() { case "$1" in /*) printf '%s\n' "$1" ;; *) printf '%s/%s\n' "$PWD" "$1" ;; esac; }

PROTOC="$(abspath "${1:?path to protoc}")"
BUF="$(abspath "${2:?path to buf}")"
PLUGIN="${PLUGIN:-${3:?path to the protoc-gen-clojure binary, or set PLUGIN}}"
PLUGIN="$(abspath "$PLUGIN")"

[ -x "$PLUGIN" ] || {
  echo "FAIL: $PLUGIN is not executable" >&2
  exit 1
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

# protoc discovers plugins as protoc-gen-<name> on PATH, so the binary's basename
# is load-bearing: it is what makes --clojure_out work.
mkdir -p bin && cp "$PLUGIN" bin/protoc-gen-clojure
# protoc and buf are passed in under `bazel test`, where they are hermetic
# runfiles rather than whatever happens to be installed.
ln -sf "$PROTOC" bin/protoc
ln -sf "$BUF" bin/buf
export PATH="$WORK/bin:$PATH"

# The path matters: namespaces derive from the file path, not the proto package,
# so this must be demo/hello.proto to emit demo/hello.clj.
mkdir -p demo
cat >demo/hello.proto <<'EOF'
edition = "2024";

package demo;

message Hello {
  string name = 1;
  int32 count = 2;
}

service Greeter {
  rpc Greet(Hello) returns (Hello);
}
EOF

check() {
  # Separate statements on purpose: bash expands every word of a `local` command
  # before performing any of its assignments, so `local dir="$1" out="$dir/x"`
  # sees an unset `dir` and dies under `set -u`.
  local dir="$1"
  local label="$2"
  local out="$dir/demo/hello.clj"
  [ -f "$out" ] || {
    echo "FAIL: $label produced no demo/hello.clj" >&2
    return 1
  }
  # Assert real content, not just an existing file: a plugin that errors politely
  # can still leave an empty output behind.
  grep -q '^(ns demo.hello' "$out" || {
    echo "FAIL: $label output has no ns form" >&2
    return 1
  }
  grep -q 'defrecord Hello' "$out" || {
    echo "FAIL: $label output has no Hello record" >&2
    return 1
  }
  grep -q 'Greeter' "$out" || {
    echo "FAIL: $label output has no service" >&2
    return 1
  }
  echo "OK: $label -> $(wc -l <"$out" | tr -d ' ') lines"
}

echo "=== 1. the plugin reports a version"
version="$(protoc-gen-clojure --version)"
echo "    $version"
[ -n "$version" ] || {
  echo "FAIL: --version printed nothing" >&2
  exit 1
}

echo "=== 2. standalone protoc --clojure_out"
mkdir -p out-protoc
protoc --clojure_out=out-protoc demo/hello.proto
check out-protoc "protoc"

echo "=== 3. buf generate with a local plugin"
cat >buf.gen.yaml <<'EOF'
version: v2
plugins:
  - local: protoc-gen-clojure
    out: out-buf
EOF
cat >buf.yaml <<'EOF'
version: v2
modules:
  - path: .
EOF
mkdir -p out-buf
buf generate
check out-buf "buf"

echo "=== 4. both frontends agree"
diff -u out-protoc/demo/hello.clj out-buf/demo/hello.clj ||
  {
    echo "FAIL: protoc and buf produced different output" >&2
    exit 1
  }
echo "OK: protoc and buf agree byte for byte"

echo
echo "distribution test passed"
