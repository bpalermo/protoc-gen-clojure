#!/usr/bin/env bash
# Workspace status for stamped image tags. Wired in via .bazelrc.
#
# Bazel runs this on every build, so it must be fast and must never fail — a
# non-zero exit breaks the build. Everything degrades to a placeholder instead.
#
# STABLE_ keys are part of the action cache key, so a new commit or version
# re-stamps the tag. Unprefixed keys are volatile and can retain a stale value
# from a previous build, which is precisely wrong for a tag that identifies an
# artifact.
#
# This is also the single source of the declared version: the release workflow
# reads STABLE_VERSION from here rather than carrying its own copy of the regex,
# because two copies of a version parser drift and the symptom only appears when
# cutting a release.
set -uo pipefail

version=$(grep -oE '^  "[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?"' \
  src/protoc_gen_clojure/plugin.clj 2>/dev/null | tr -d ' "' | head -1)
echo "STABLE_VERSION ${version:-0.0.0-unknown}"

commit=$(git rev-parse --short=12 HEAD 2>/dev/null)
echo "STABLE_GIT_COMMIT ${commit:-unknown}"

# Marks images built from a non-pristine tree, so an accidental push is
# identifiable.
#
# `git status --porcelain` rather than `git diff --quiet HEAD`: the latter ignores
# untracked files, and untracked files change what gets built here — several targets
# glob (test/golden, test/proto), so a stray file lands in the output while the
# stamp still says clean. --porcelain respects .gitignore, so bazel-* symlinks and
# dist/ do not count.
if [ -z "$(git status --porcelain 2>/dev/null)" ]; then
  echo "STABLE_GIT_DIRTY clean"
else
  echo "STABLE_GIT_DIRTY dirty"
fi

# Whether this build may move the `latest` image tag.
#
# Only a stable version does. A prerelease (0.1.0-rc1) must not move `latest`, or
# `docker pull …:latest` hands someone a release candidate.
#
# The value is the literal tag or the string "none" rather than true/false, because
# Go templates treat any non-empty string as truthy — "false" would read as true and
# the guard would silently do nothing.
case "${version:-}" in
  *-*) echo "STABLE_MOVING_TAG none" ;;
  "") echo "STABLE_MOVING_TAG none" ;;
  *) echo "STABLE_MOVING_TAG latest" ;;
esac
