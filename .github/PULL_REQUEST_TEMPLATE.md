<!--
Keep it short. The interesting part is usually "how it was verified" and
"what could go wrong", not a restatement of the diff.
-->

## What

<!-- One or two sentences. What changes for a user of the plugin? -->

## Why

<!-- The problem this solves. If it fixes a bug, what did the bug look like? -->

## How it was verified

<!--
Say what you actually ran, and what the result was. "Tests pass" is not a
verification claim on its own — `bazel test //...` proves very little about a
distributed binary, because it exercises the JVM plugin inside Bazel.

If the change touches the emitter, the plugin protocol, or the native build,
say whether the native artifact was run.
-->

- [ ] `bazel test //...` (lint included; `bazel run //bazel/dev:format` fixes formatting)
- [ ] Goldens reviewed — a diff under `test/golden/` **is** the emitter change.
      Regenerate with `bazel run //test:update_golden`; never hand-edit.
- [ ] Native artifact exercised: `bazel test //test:distribution_test`.
      Required for anything touching interop, `-main`, or the build — reflection
      and class-initialisation bugs pass on the JVM and only fail here.
- [ ] Editions window still correct if `protobuf-java` moved
      (`//test:plugin_test`).
- [ ] `plugin.clj` remains a pure function of the `CodeGeneratorRequest`
      (`//test:sandbox_conformance_test`) — no filesystem, network or env, or the
      plugin stops being publishable to the BSR.
- [ ] Version bumped in **both** `src/protoc_gen_clojure/plugin.clj` and `MODULE.bazel` if
      this is a release; the release workflow refuses a mismatch.

## Risks and follow-ups

<!--
Anything knowingly left undone, and why. Known gaps are much cheaper to read
here than to rediscover later.
-->
