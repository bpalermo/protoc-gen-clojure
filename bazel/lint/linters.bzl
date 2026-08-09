"""Lint aspects, exposed as ordinary Bazel tests.

Wiring lint as `lint_test` targets rather than as an `aspect lint` invocation
means `bazel test //...` covers it and CI needs no separate lint job — the same
arrangement nubank/park uses.

Three linters, one per language actually present here:

  buf         the fixture protos, which are the plugin's input contract
  shellcheck  the shell scripts. This is not speculative: shellcheck's SC2318
              flags exactly the bug that already shipped here —
              `local dir="$1" out="$dir/x"`, where bash expands every word of a
              `local` before performing any of its assignments, so the script
              died under `set -u`. Verified against shellcheck v0.11.0.
"""

load("@aspect_rules_lint//lint:buf.bzl", "lint_buf_aspect")
load("@aspect_rules_lint//lint:lint_test.bzl", "lint_test")
load("@aspect_rules_lint//lint:shellcheck.bzl", "lint_shellcheck_aspect")

buf = lint_buf_aspect(
    config = Label("//bazel/lint:buf-lint.yaml"),
)

shellcheck = lint_shellcheck_aspect(
    binary = Label("@aspect_rules_lint//lint:shellcheck_bin"),
    config = Label("//:.shellcheckrc"),
)

buf_test = lint_test(aspect = buf)
shellcheck_test = lint_test(aspect = shellcheck)

# No buildifier aspect, deliberately. Its binaries live in repos internal to the
# buildifier_prebuilt module, so a lint_test cannot reach them in runfiles — it
# fails with a bare "Unable to locate buildifier runfile", and re-exporting the
# extension repos does not help. nubank/park defines the aspect but only ever
# instantiates buf_test, so this is unproven there too. Revisit if buildifier
# starts exporting its binaries, or run it outside Bazel.
