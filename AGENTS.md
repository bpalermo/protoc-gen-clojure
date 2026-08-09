# protoc-gen-clojure

A `protoc`/`buf` codegen plugin, written in Clojure, that emits Clojure. It lets a
Clojure project be an ordinary entry in `buf.gen.yaml` next to
`protocolbuffers/go` and `protocolbuffers/java`.

Built with Bazel (bzlmod). Published as a Bazel module and as prebuilt native
binaries (GraalVM `native-image`), so consumers need neither a JVM nor Clojure to
generate a `.clj` file.

## Layout

| Path | What lives there |
| --- | --- |
| `src/clj_grpc/plugin.clj` | The whole plugin: request parsing, naming, emitter, `-main`. |
| `bazel/defs.bzl` | `clojure_proto_library` (the public rule) and `proto_transitive_descriptor_sets`. |
| `bazel/toolchain.bzl` | `protoc_gen_clojure_toolchain` — carries the plugin executable. |
| `bazel/extensions.bzl` | Module extension that downloads the prebuilt release binaries. |
| `bazel/versions.bzl` | Release checksums. **Empty in git on purpose** (see below). |
| `bazel/native_image.bzl` | Our own native-image rule; see below for why not rules_graalvm's. |
| `bazel/lint/linters.bzl` | buf and shellcheck aspects, wired as `lint_test` targets. |
| `bazel/tools/pin_versions.sh` | `bazel run //bazel/tools:pin_versions` — writes release checksums. |
| `test/plugin_test.clj` | Unit tests for the protocol and the emitter. |
| `test/proto/` | Fixture protos (proto2, proto3, editions 2023/2024) + generation target. |
| `test/golden/` | Checked-in expected output. Generated, not hand-written. |
| `MODULE.bazel` | Dependency split is load-bearing — see below. |

## Common commands

```sh
bazel build //...                          # everything
bazel test  //...                          # everything

bazel test  //test:plugin_test             # emitter + protocol unit tests
bazel test  //test:golden_test             # emission regression
bazel test  //test:sandbox_conformance_test # BSR purity check
bazel run   //test:update_golden           # refresh goldens after an intended change

bazel build //src/clj_grpc:protoc_gen_clojure_jvm  # JVM launcher (fast, used by tests)
bazel build //test/proto:generated                 # see what the emitter produces

bazel build //src/clj_grpc:protoc_gen_clojure      # native binary (GraalVM, ~25s)
bazel test  //test:distribution_test               # run it under real protoc + buf
```

The native binary **is** a Bazel target — see "Things that will bite you".

The plugin speaks the protoc plugin protocol on stdin/stdout, so you can also
drive it by hand:

```sh
protoc --plugin=protoc-gen-clojure=$(bazel cquery --output=files //src/clj_grpc:protoc_gen_clojure_jvm) \
       --clojure_out=/tmp/out foo.proto
```

## Things that will bite you

**`plugin.clj` must stay a pure function of the `CodeGeneratorRequest`.** No
filesystem, no network, no environment, no `System/getenv`, no `slurp`/`spit`.
The Buf Schema Registry only runs remote plugins that satisfy this, and
`//test:sandbox_conformance_test` greps for the banned forms. `System/in` and
`System/out` are the protocol, so they're fine.

**Nothing but the response may touch stdout.** A stray `println` corrupts the
`CodeGeneratorResponse` and protoc reports an unintelligible parse error.
Failures are reported by setting `.setError` on the response, not by throwing —
otherwise the user just sees "plugin failed with status code 1".

**Do no feature resolution.** The emitted file embeds the `FileDescriptorProto`
verbatim (base64) and lets protobuf-java's `FileDescriptor/buildFrom` resolve
edition features at load time. That's why a new edition needs no codegen change.
Keep it that way.

**Never hardcode a maximum edition.** `max-supported-edition` *probes* the linked
protobuf-java by trying to build a descriptor at each edition, descending.
Bumping `protobuf-java` in `MODULE.bazel` raises it automatically. Enum
membership is not evidence of support — 4.35.1 lists `EDITION_2026` but cannot
resolve it. Hardcoding is the exact bug that stranded protoc's own C++ plugins
when edition 2024 shipped.

**Editions and generated output are pinned by tests.** `//test:plugin_test`
asserts the advertised window; `//test:golden_test` diffs generated output
against `test/golden/`. If you intend an emitter change, run
`bazel run //test:update_golden` and review the resulting diff — that diff *is*
the change. Don't hand-edit files under `test/golden/`.

**`bazel/versions.bzl` is deliberately empty in git.** The native binaries are
built by the same release that publishes the module, so their checksums can't
exist at tag time. The release workflow builds them, rewrites this file, and
publishes its own source archive; `.bcr/source.template.json` points at that
asset instead of GitHub's auto-generated tarball. Consequence: a consumer must
depend on a *released* version. From a git checkout, pass
`//src/clj_grpc:protoc_gen_clojure_jvm` to `clojure_proto_library`'s `plugin`
attr instead of relying on the toolchain — which is exactly what
`//test/proto:generated` does.

**The native binary is built by `//bazel:native_image.bzl`, a rule of our own —
not by `rules_graalvm`.** Its `native_image` rule crashes Bazel on macOS:
it routes through `apple_support.run`, which puts `SDKROOT` in the action env,
and Bazel's own `XcodeLocalEnvProvider` then injects `SDKROOT` again — "Multiple
entries with same key: SDKROOT". Not fixable from here.

rules_graalvm is still a dependency, but only to fetch a pinned GraalVM SDK —
never for its rules. Our rule runs the action `local` and unsandboxed with the
ambient environment, because native-image shells out to the platform linker
(clang/ld on macOS, gcc on Linux) and Bazel's scrubbed env leaves it unable to
find one. That compromise is deliberate and documented in the rule itself.

**`rules_clojure`, `rules_jvm_external`, `rules_java` and `rules_shell` are
`dev_dependency = True`, and that is not cosmetic.** A published module's non-dev
deps must all resolve from BCR, and `rules_clojure` currently needs a
`git_override` for its Bazel 9 compat patches (griffinbank#108). `git_override`
is honoured only in the root module, so making them dev deps is what keeps the
module publishable *and* buildable here. Consumers use the prebuilt binary and
need none of them. Don't promote them.

**`clojure_proto_library` is a real rule, not a genrule, on purpose.** protoc
needs every transitive proto dependency resolvable. `--proto_path` can't reach
edition 2024's `import option java_features.proto` (protobuf exports it in no
filegroup), so we use `--descriptor_set_in` with the *transitive* descriptor
sets — and those are only available via `ProtoInfo`, which a genrule can't see.

**Bazel is pinned by `.bazelversion` (9.2.0).** Both bazelisk and CI's
setup-bazel read it. Unpinned, a Bazel 10 release would silently change the build
of a repo whose dependency story is Bazel-9-specific.

**Bazel 9 removed `ProtoInfo`, `JavaInfo` and `CcInfo` from the Starlark
globals.** Load them from their modules (`@protobuf//bazel/common:proto_info.bzl`
etc.). `.bazelrc` also pins `--tool_java_language_version=21`, without which
`rules_clojure`'s persistent worker fails on `java.util.HexFormat`.

## Naming conventions in generated code

- `acme/greeter/greeter.proto` → namespace `acme.greeter.greeter`, file
  `acme/greeter/greeter.clj`. Underscores in path segments become hyphens in the
  namespace; Clojure's own munging puts them back on disk.
- Field names are kebab-cased to match `clj-grpc.codec`'s `:kebab` default, so
  generated records and plain maps stay interchangeable.
- `clj-grpc.runtime.service` is required only when the file actually declares a
  service — otherwise a message-only file would drag grpc-java onto the
  classpath of a project that never asked for RPC.

## Why the namespace is `clj-grpc.plugin`

Because that's what it generates code *for*: emitted files require
`clj-grpc.codec` and `clj-grpc.runtime`. This repo is clj-grpc's codegen,
distributed separately so protoc/buf users need neither the gRPC runtime nor a
JVM — the same relationship `protoc-gen-go` has with its runtime module.
Behavioural tests of the *emitted* code live in clj-grpc, which owns that
runtime; keeping them there is what makes the two module graphs acyclic.
