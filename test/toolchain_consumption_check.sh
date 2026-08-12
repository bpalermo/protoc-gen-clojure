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
  # A prerelease must not be picked up silently: it would report the consumption story
  # as working while testing an artifact no consumer is told to use. But the filter has
  # to look PAST prereleases rather than at only the newest release — with --limit 1,
  # cutting v0.2.0-rc1 would make this claim there is no stable release at all, while
  # v0.1.0 sits right there. Drafts are excluded too: their assets are not fetchable by
  # an anonymous consumer, which is precisely what this checks.
  tag="$(gh release list --repo "$repo" --limit 50 --json tagName,isPrerelease,isDraft \
    --jq 'map(select(.isPrerelease == false and .isDraft == false)) | .[0].tagName // empty')"
  [[ -n "$tag" ]] || {
    echo "no stable, non-draft release found in the 50 most recent in $repo" >&2
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

# Which package holds the public API, read from the archive rather than assumed.
# It moved from //bazel to //clojure in 0.2.0, and this script checks whichever
# release is newest — so hardcoding either label makes it wrong for half the
# releases it can be pointed at, and wrong in a way that looks like the toolchain
# is broken rather than like the check is.
if tar tzf "$archive" | grep -q '^protoc-gen-clojure/clojure/defs\.bzl$'; then
  api_pkg="clojure"
elif tar tzf "$archive" | grep -q '^protoc-gen-clojure/bazel/defs\.bzl$'; then
  api_pkg="bazel"
else
  echo "FAIL: $archive has no defs.bzl under bazel/ or clojure/" >&2
  exit 1
fi
echo "    public API package: //$api_pkg"

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

plugin = use_extension("@protoc_gen_clojure//$api_pkg:extensions.bzl", "toolchains")
use_repo(plugin, "protoc_gen_clojure_toolchains")

register_toolchains("@protoc_gen_clojure_toolchains//:all")
EOF

# The loads are printed rather than heredoc'd because only they need $api_pkg
# expanded, and the rest of this file contains backticks: an unquoted heredoc would
# run `plugin` as a command substitution instead of writing it as a comment.
{
  printf 'load("@protobuf//bazel:proto_library.bzl", "proto_library")\n'
  printf 'load("@protoc_gen_clojure//%s:defs.bzl", "clojure_proto_library")\n' "$api_pkg"
  cat <<'EOF'

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
} >BUILD.bazel

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
#
# Pull the plugin path out of the action itself rather than searching the external
# directory: the toolchain declares a repo per platform, so a `find` can return another
# platform's binary and report a hash that had nothing to do with this build.
# `--plugin=protoc-gen-clojure=` is the exact argument protoc was given.
#
# tr/sed rather than `grep -o`: the pattern begins with `--`, and grep implementations
# differ on how they take that (ugrep exits 2 on `-oE --`, which silently turned this
# into a skipped check the first time). aquery quotes each argument, so the token arrives
# as '--plugin=protoc-gen-clojure=external/...'; the quotes are dropped with tr rather
# than matched in the pattern, because BSD sed rejected the \{0,1\} interval that took
# — and, being inside a pipefail pipeline, took the whole check down with exit 2 instead
# of reporting anything.
plugin_arg="$(bazel aquery //:hello_clj 2>/dev/null |
  tr ' ' '\n' |
  tr -d "'" |
  sed -n 's/^--plugin=protoc-gen-clojure=//p' |
  head -1 || true)"
plugin_arg="${plugin_arg%,}"

# Fatal, not a warning. This is the assertion that the toolchain — rather than anything
# local — did the work, so failing to establish it must fail the check.
[[ -n "$plugin_arg" ]] || {
  echo "FAIL: could not determine which plugin the action used" >&2
  exit 1
}
case "$plugin_arg" in
  *external/protoc_gen_clojure*toolchains*) ;;
  *)
    echo "FAIL: codegen did not use a toolchain-provided binary: $plugin_arg" >&2
    exit 1
    ;;
esac

echo "==> plugin used: $plugin_arg"

# `execution_root`, not `execroot`: Bazel 9 does not have the latter key and `bazel info`
# exits 2 for an unknown one, which under `set -e` killed this check silently — no
# message, just a non-zero exit after a successful build.
#
# Guarded with `|| true` and reported as a warning rather than a failure, because the
# hash is DIAGNOSTIC. The assertion that matters — that codegen went through a
# toolchain-provided binary — is the fatal check above and does not depend on this key
# continuing to exist.
execution_root="$(bazel info execution_root 2>/dev/null || true)"
bin="${execution_root:+$execution_root/$plugin_arg}"
if [[ -n "$bin" && -f "$bin" ]]; then
  if command -v sha256sum >/dev/null 2>&1; then
    echo "    sha256 $(sha256sum "$bin" | cut -d' ' -f1)"
  else
    echo "    sha256 $(shasum -a 256 "$bin" | cut -d' ' -f1)"
  fi
  echo "    compare against BINARIES in the module's bazel/versions.bzl for this platform"
else
  echo "WARN: could not hash $plugin_arg — 'bazel info execution_root' gave nothing" >&2
fi

echo "==> OK: $tag is consumable with no Clojure, JVM or GraalVM in the consumer"
