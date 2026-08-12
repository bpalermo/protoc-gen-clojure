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
# Prior jobs' artifacts are extracted into the workspace by the caller, which is
# how the per-platform native binaries reach us: they cannot be built here,
# because GraalVM does not cross-compile and this runs on one runner.
#
# They do NOT land under ./artifacts/, despite that being what release_ruleset's
# own comment claims. It runs actions/download-artifact with no `path` and no
# `name`, whose documented behaviour is one directory per artifact under
# $GITHUB_WORKSPACE. Observed in the v0.1.0-rc1 run log:
#
#   No input name, artifact-ids or pattern filtered specified, downloading all artifacts
#   An extra directory with the artifact name will be created for each download
#   Starting download of artifact to: /home/runner/work/…/protoc-gen-clojure/binary-linux_x86_64
set -euo pipefail

TAG="${1:?tag, e.g. v0.1.0}"
VERSION="${TAG#v}"

# Collect the binaries the matrix built. Fail loudly on a miscount rather than
# releasing a partial set — four platforms, no more, no fewer. This assertion is
# what turned a silently-empty release into a failed job.
#
# Searched by filename rather than by the artifact directory name, so renaming the
# upload cannot quietly halve a release, and so a caller that does set `path`
# keeps working. -maxdepth 2 covers both layouts (./binary-*/f and ./artifacts/f)
# without descending the tree, and find does not follow the bazel-* symlinks
# because -L is not given — otherwise it would walk the whole output base.
mapfile -t BINARIES < <(find . -maxdepth 2 -type f -name 'protoc-gen-clojure_*_*' | sort)
if [ "${#BINARIES[@]}" -ne 4 ]; then
  echo "expected 4 extracted native binaries in the workspace, found ${#BINARIES[@]}:" >&2
  printf '  %s\n' "${BINARIES[@]}" >&2
  echo "the matrix uploads them as binary-<os>_<arch>; check that those jobs ran" >&2
  exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
for b in "${BINARIES[@]}"; do
  cp "$b" "$STAGE/$(basename "$b")"
done
chmod +x "$STAGE"/*

# Pin the binaries' checksums into clojure/versions.bzl, then build the module
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

plugin = use_extension("@protoc_gen_clojure//clojure:extensions.bzl", "toolchains")
use_repo(plugin, "protoc_gen_clojure_toolchains")

register_toolchains("@protoc_gen_clojure_toolchains//:all")
\`\`\`

Consumers need no Clojure, no JVM and no GraalVM: the toolchain fetches the
prebuilt binary for the host platform.
EOF
