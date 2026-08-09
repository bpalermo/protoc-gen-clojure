#!/usr/bin/env bash
# Prepare release assets. Called by bazel-contrib/.github's release_ruleset
# workflow with the tag as its only argument; stdout becomes the release notes.
#
# This script exists because release_ruleset deliberately has no
# release_prep_command input: a dispatch-supplied command would not be covered by
# the attestation, so the path is forced to be a file in the repo. That is also
# why BCR only trusts source archives produced this way — it verifies the signer
# workflow is bazel-contrib/.github's, so an archive we attested ourselves is
# rejected.
#
# Prior jobs' artifacts are already extracted under ./artifacts/ by the caller,
# which is how the per-platform native binaries reach us: they cannot be built
# here, because GraalVM does not cross-compile and this runs on one runner.
set -euo pipefail

TAG="${1:?tag, e.g. v0.1.0}"
VERSION="${TAG#v}"

# Collect the binaries the matrix built. Fail loudly on a miscount rather than
# releasing a partial set — four platforms, no more, no fewer.
mapfile -t BINARIES < <(find artifacts -type f -name 'protoc-gen-clojure_*_*' | sort)
if [ "${#BINARIES[@]}" -ne 4 ]; then
  echo "expected 4 native binaries under artifacts/, found ${#BINARIES[@]}:" >&2
  printf '  %s\n' "${BINARIES[@]}" >&2
  exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
for b in "${BINARIES[@]}"; do
  cp "$b" "$STAGE/$(basename "$b")"
done
chmod +x "$STAGE"/*

# Pin the binaries' checksums into bazel/versions.bzl, then build the module
# archive so it carries them. This ordering is the whole reason the archive is
# not GitHub's auto-generated tarball: the hashes cannot exist before the
# binaries do.
bazel run //bazel/tools:pin_versions -- "$VERSION" "$STAGE" >&2

bazel build //bazel/dev:module_archive //src/protoc_gen_clojure:protoc_gen_clojure_jvm_deploy.jar >&2

# Assets land at the repo root, where release_ruleset's release_files globs look.
cp "$(bazel cquery --output=files //bazel/dev:module_archive 2>/dev/null | tail -1)" \
  "protoc-gen-clojure-module.tar.gz"
cp "$(bazel cquery --output=files //src/protoc_gen_clojure:protoc_gen_clojure_jvm_deploy.jar 2>/dev/null | tail -1)" \
  "protoc-gen-clojure_${VERSION}.jar"
cp "$STAGE"/protoc-gen-clojure_* .
chmod +x protoc-gen-clojure_*_* 2>/dev/null || true

# Release notes on stdout.
cat <<EOF
## Install

\`protoc\` discovers plugins as \`protoc-gen-<name>\` on PATH, so the file name is
load-bearing — it is what makes \`--clojure_out\` work.

\`\`\`sh
curl -fsSLo protoc-gen-clojure \\
  "https://github.com/${GITHUB_REPOSITORY:-bpalermo/protoc-gen-clojure}/releases/download/${TAG}/protoc-gen-clojure_${VERSION}_linux_x86_64"
chmod +x protoc-gen-clojure && mv protoc-gen-clojure /usr/local/bin/
\`\`\`

On macOS, clear the quarantine attribute: \`xattr -d com.apple.quarantine <path>\`.
Binaries are unsigned.

## Bazel

\`\`\`starlark
bazel_dep(name = "protoc_gen_clojure", version = "${VERSION}")

plugin = use_extension("@protoc_gen_clojure//bazel:extensions.bzl", "toolchains")
use_repo(plugin, "protoc_gen_clojure_toolchains")

register_toolchains("@protoc_gen_clojure_toolchains//:all")
\`\`\`

Consumers need no Clojure, no JVM and no GraalVM: the toolchain fetches the
prebuilt binary for the host platform.
EOF
