"""Checksums for the released native binaries, keyed by Bazel platform.

DELIBERATELY EMPTY IN GIT, populated only in the released source archive.

The chicken-and-egg: the binaries are built by the same release that publishes
this module, so their sha256 cannot be known when the tag is cut. The release
workflow therefore builds the binaries, records their checksums here, and
packages its own source archive containing this populated file. `.bcr/
source.template.json` points at that asset rather than GitHub's auto-generated
tarball, so the archive BCR serves always has real checksums.

Consequence: a consumer must depend on a released version, not on a raw git
checkout. Building from a git checkout is a development flow, and it uses
//src/protoc_gen_clojure:protoc_gen_clojure directly rather than this table.
"""

# version this table describes; the release workflow rewrites it.
VERSION = "0.0.0-dev"

# "os_arch": (sha256, asset name)
BINARIES = {
    # "linux_x86_64": ("<sha256>", "protoc-gen-clojure_0.1.0_linux_x86_64"),
    # "linux_aarch64": (...),
    # "darwin_x86_64": (...),
    # "darwin_arm64": (...),
}

# Bazel constraint values per key above.
PLATFORMS = {
    "linux_x86_64": ["@platforms//os:linux", "@platforms//cpu:x86_64"],
    "linux_aarch64": ["@platforms//os:linux", "@platforms//cpu:aarch64"],
    "darwin_x86_64": ["@platforms//os:macos", "@platforms//cpu:x86_64"],
    "darwin_arm64": ["@platforms//os:macos", "@platforms//cpu:arm64"],
}
