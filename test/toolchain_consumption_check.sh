#!/usr/bin/env bash
#
# Does the prebuilt-toolchain story actually work for a consumer?
#
# The README's headline claim is that a consumer needs no Clojure, no JVM and no
# GraalVM: it declares a bazel_dep, registers the toolchain, and codegen happens
# through a native binary the toolchain downloads. Nothing in `bazel test //...`
# tests that, and it cannot: every target in this repo uses a graph-built plugin.
# //test:distribution_test comes closest and still runs a binary Bazel just built.
#
# This builds a module OUTSIDE the repo, resolves protoc_gen_clojure from a RELEASE
# ASSET by integrity, and generates Clojure from a .proto with no `plugin` attribute
# anywhere — so the only way it can succeed is through the toolchain.
#
# Deliberately not examples/bzlmod: that module local_path_override's to `../..`, so
# in-repo it proves the extension and the binary but says nothing about the published
# archive. BCR's presubmit is what vendors it for real, and on #9991 that step has
# been blocked awaiting a maintainer since the release — which is why this exists.
#
# Usage:
#   test/toolchain_consumption_check.sh            # newest release
#   test/toolchain_consumption_check.sh v0.1.0     # a specific tag
#
# Needs: bazel, gh (authenticated), and network. Exits non-zero on any failure.
set -euo pipefail

readonly repo="bpalermo/protoc-gen-clojure"

# Resolved before any cd, since everything below runs in a temp dir.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly repo_root

tag="${1:-}"
if [[ -z "$tag" ]]; then
  tag="$(gh release list --repo "$repo" --limit 1 --json tagName,isPrerelease \
    --jq 'map(select(.isPrerelease == false)) | .[0].tagName')"
  # A prerelease must not be picked up silently: it would report the consumption
  # story as working while testing an artifact no consumer is told to use.
  [[ -n "$tag" && "$tag" != "null" ]] || {
    echo "no stable release found in $repo" >&2
    exit 1
  }
fi
echo "==> checking the toolchain path against $tag"

version="${tag#v}"
readonly archive="protoc-gen-clojure-module.tar.gz"
readonly url="https://github.com/$repo/releases/download/$tag/$archive"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"

# The integrity is computed from the asset rather than being passed in, so the check
# cannot pass against a locally modified archive: this is the same bytes Bazel will
# fetch, hashed the way Bazel expresses it.
echo "==> downloading $archive"
gh release download "$tag" --repo "$repo" --pattern "$archive" --dir . >/dev/null
integrity="sha256-$(openssl dgst -sha256 -binary "$archive" | openssl base64 -A)"
echo "    integrity $integrity"

mkdir -p consumer/example
cd consumer
# Pin the same Bazel this repo pins. The module declares 9.x only, and letting
# bazelisk pick "latest" would make the check fail for a reason that has nothing to do
# with the toolchain the day Bazel 10 ships.
if [[ -f "$repo_root/.bazelversion" ]]; then
  cp "$repo_root/.bazelversion" .bazelversion
  echo "    bazel $(cat .bazelversion)"
fi

cat >MODULE.bazel <<EOF
module(name = "toolchain_consumption_check", version = "0.0.0")

bazel_dep(name = "protoc_gen_clojure", version = "$version")
bazel_dep(name = "protobuf", version = "35.1")

archive_override(
    module_name = "protoc_gen_clojure",
    integrity = "$integrity",
    strip_prefix = "protoc-gen-clojure",
    urls = ["$url"],
)

plugin = use_extension("@protoc_gen_clojure//bazel:extensions.bzl", "toolchains")
use_repo(plugin, "protoc_gen_clojure_toolchains")

register_toolchains("@protoc_gen_clojure_toolchains//:all")
EOF

cat >BUILD.bazel <<'EOF'
load("@protobuf//bazel:proto_library.bzl", "proto_library")
load("@protoc_gen_clojure//bazel:defs.bzl", "clojure_proto_library")

proto_library(
    name = "hello_proto",
    srcs = ["example/hello.proto"],
    strip_import_prefix = "",
)

# No `plugin` attribute. The binary can only come from the registered toolchain,
# which is the one thing this check exists to prove.
clojure_proto_library(
    name = "hello_clj",
    outs = ["example/hello.clj"],
    proto = ":hello_proto",
)
EOF

cat >example/hello.proto <<'EOF'
syntax = "proto3";

package example;

message Greeting {
  string text = 1;
}
EOF

echo "==> building //:hello_clj (downloads and runs the released binary)"
bazel build //:hello_clj

out="$(bazel cquery --output=files //:hello_clj 2>/dev/null | head -1)"
[[ -f "$out" ]] || {
  echo "FAIL: no output file produced" >&2
  exit 1
}

# Assert on content, not just exit status: a plugin that wrote an empty file, or that
# failed in a way protoc reported as success, would otherwise pass.
for want in '(ns example.hello' 'rt/file-descriptor' 'defrecord Greeting' 'Greeting->proto'; do
  grep -qF "$want" "$out" || {
    echo "FAIL: generated output is missing '$want'" >&2
    echo "--- $out" >&2
    cat "$out" >&2
    exit 1
  }
done
echo "==> generated $(basename "$out"): $(wc -l <"$out" | tr -d ' ') lines, all expected forms present"

# Name the binary that did the work, and its hash. Without this the check could pass
# while silently using something other than the downloaded asset, and a reader has no
# way to tell from a green tick.
bin="$(find "$(bazel info output_base)/external" -type f -name 'protoc-gen-clojure' 2>/dev/null | head -1)"
if [[ -n "$bin" ]]; then
  echo "==> plugin used: $bin"
  if command -v sha256sum >/dev/null 2>&1; then
    echo "    sha256 $(sha256sum "$bin" | cut -d' ' -f1)"
  else
    echo "    sha256 $(shasum -a 256 "$bin" | cut -d' ' -f1)"
  fi
  echo "    compare against BINARIES in the module's bazel/versions.bzl for this platform"
else
  echo "WARN: could not locate the fetched binary to report its hash" >&2
fi

echo "==> OK: $tag is consumable with no Clojure, JVM or GraalVM in the consumer"
