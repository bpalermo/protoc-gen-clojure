"""Module extension that fetches the prebuilt protoc-gen-clojure binary.

This is what makes the module cheap to depend on: a consumer gets a native
executable for its host platform and needs no Clojure, no JVM and no GraalVM to
generate a .clj file.

The repos come in two layers. One repo per platform holds the downloaded binary
and its toolchain implementation; a single hub repo declares the `toolchain()`
targets that point at them. The split is what makes
`register_toolchains("@protoc_gen_clojure_toolchains//:all")` resolve to every
platform — a `:all` pattern only sees one package, so the toolchain declarations
have to live together in that one package.
"""

load("//clojure:versions.bzl", "BINARIES", "PLATFORMS", "VERSION")

_RELEASE_URL = "https://github.com/bpalermo/protoc-gen-clojure/releases/download/v{version}/{asset}"

_BINARY_BUILD = """\
load("@protoc_gen_clojure//clojure:toolchain.bzl", "protoc_gen_clojure_toolchain")

package(default_visibility = ["//visibility:public"])

exports_files(["protoc-gen-clojure"])

protoc_gen_clojure_toolchain(
    name = "impl",
    plugin = "protoc-gen-clojure",
)
"""

def _binary_repo_impl(rctx):
    sha256, asset = BINARIES[rctx.attr.platform]
    rctx.download(
        url = _RELEASE_URL.format(version = VERSION, asset = asset),
        output = "protoc-gen-clojure",
        sha256 = sha256,
        executable = True,
    )
    rctx.file("BUILD.bazel", _BINARY_BUILD)

_binary_repo = repository_rule(
    implementation = _binary_repo_impl,
    doc = "Downloads one platform's prebuilt protoc-gen-clojure.",
    attrs = {"platform": attr.string(mandatory = True)},
)

_TOOLCHAIN = """\
toolchain(
    name = "{platform}",
    exec_compatible_with = {constraints},
    toolchain = "@protoc_gen_clojure_{platform}//:impl",
    toolchain_type = "@protoc_gen_clojure//clojure:toolchain_type",
)
"""

def _hub_impl(rctx):
    lines = ['package(default_visibility = ["//visibility:public"])\n']
    for platform in rctx.attr.platforms:
        lines.append(_TOOLCHAIN.format(
            platform = platform,
            constraints = repr(PLATFORMS[platform]).replace("'", '"'),
        ))
    rctx.file("BUILD.bazel", "\n".join(lines))

_hub = repository_rule(
    implementation = _hub_impl,
    doc = "Declares one toolchain() per platform, so //:all registers them all.",
    attrs = {"platforms": attr.string_list(mandatory = True)},
)

def _toolchains_impl(mctx):
    if not BINARIES:
        fail(
            "protoc_gen_clojure: no prebuilt binaries are pinned in this checkout.\n" +
            "clojure/versions.bzl is populated only in a released source archive, so\n" +
            "depend on a released version (BCR or the release tarball) rather than on\n" +
            "a git checkout. To build from source instead, pass\n" +
            "@protoc_gen_clojure//src/protoc_gen_clojure:protoc_gen_clojure_jvm to\n" +
            "clojure_proto_library's `plugin` attribute.",
        )
    platforms = sorted(BINARIES.keys())
    for platform in platforms:
        _binary_repo(name = "protoc_gen_clojure_" + platform, platform = platform)
    _hub(name = "protoc_gen_clojure_toolchains", platforms = platforms)
    return mctx.extension_metadata(reproducible = True)

toolchains = module_extension(
    implementation = _toolchains_impl,
    doc = "Fetches prebuilt protoc-gen-clojure binaries and exposes them as toolchains.",
)
