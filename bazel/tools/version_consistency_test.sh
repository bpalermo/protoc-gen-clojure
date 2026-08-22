#!/usr/bin/env bash
# Every version this repository declares or documents must be THE version.
#
# MODULE.bazel's literal is the one that cannot be derived (it is read during
# module resolution, before any build, and BCR validates it inside the archive),
# so it is the reference. Everything else must agree with it:
#
#   - plugin.clj: what the shipped binary reports at runtime. Also asserted
#     against the tag by the release workflow, but that fires at release time;
#     this fires on every `bazel test //...`.
#   - README.md: the curl and bazel_dep install snippets. These ship in the
#     module archive and CANNOT be stamped or release-checked into correctness —
#     the 0.3.1 bump initially missed both, and only review caught it. This test
#     is what makes that class of miss fail closed.
#   - examples/bzlmod: inert where it executes (local_path_override wins), but
#     it ships in the archive as the documented consumer example and had been
#     stale for two releases.
#
# The plugin.clj pattern is the same one bazel/tools/workspace_status.sh uses.
# That is a second copy of a parser, which this repository normally refuses —
# tolerated here because the copies guard each other: if they drift, one of them
# stops matching and this test fails loudly instead of both drifting silently.
set -euo pipefail

MODULE="${1:?path to MODULE.bazel}"
PLUGIN="${2:?path to plugin.clj}"
README="${3:?path to README.md}"
EXAMPLE="${4:?path to examples/bzlmod/MODULE.bazel}"

declared=$(grep -oE 'version = "[^"]+"' "$MODULE" | head -1 | cut -d'"' -f2)
[ -n "$declared" ] || {
  echo "FAIL: no version declared in $MODULE" >&2
  exit 1
}
echo "MODULE.bazel declares $declared"

fail=0
check() {
  local site="$1" found="$2"
  if [ -z "$found" ]; then
    echo "FAIL: could not extract a version from $site" >&2
    fail=1
  elif [ "$found" != "$declared" ]; then
    echo "FAIL: $site says '$found' but MODULE.bazel declares '$declared'" >&2
    fail=1
  else
    echo "ok: $site agrees ($found)"
  fi
}

plugin_version=$(grep -oE '^  "[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?"' "$PLUGIN" |
  tr -d ' "' | head -1)
check "plugin.clj" "$plugin_version"

readme_curl=$(grep -oE '^VERSION=[0-9][^ ]*' "$README" | head -1 | cut -d= -f2)
check "README curl snippet" "$readme_curl"

readme_dep=$(grep -oE 'name = "protoc_gen_clojure", version = "[^"]+"' "$README" |
  head -1 | cut -d'"' -f4)
check "README bazel_dep snippet" "$readme_dep"

example_dep=$(grep -oE 'name = "protoc_gen_clojure", version = "[^"]+"' "$EXAMPLE" |
  head -1 | cut -d'"' -f4)
check "examples/bzlmod pin" "$example_dep"

exit "$fail"
