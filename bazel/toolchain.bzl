"""A toolchain carrying the protoc-gen-clojure executable.

Why a toolchain rather than a plain label: the plugin is a build tool that must
be selected per host platform, and consumers should get a prebuilt native binary
rather than compiling Clojure. Toolchain resolution is Bazel's mechanism for
exactly that, and it keeps `clojure_proto_library` free of any per-platform
select().
"""

ProtocGenClojureInfo = provider(
    doc = "The protoc-gen-clojure executable.",
    fields = {
        "plugin": "File — the executable protoc invokes.",
        "files": "depset — runfiles needed to run it.",
    },
)

def _protoc_gen_clojure_toolchain_impl(ctx):
    plugin = ctx.executable.plugin
    return [
        platform_common.ToolchainInfo(
            protoc_gen_clojure = ProtocGenClojureInfo(
                plugin = plugin,
                files = depset(
                    [plugin],
                    transitive = [ctx.attr.plugin[DefaultInfo].default_runfiles.files],
                ),
            ),
        ),
    ]

protoc_gen_clojure_toolchain = rule(
    implementation = _protoc_gen_clojure_toolchain_impl,
    doc = "Declares a protoc-gen-clojure binary as a toolchain.",
    attrs = {
        "plugin": attr.label(
            doc = "The protoc-gen-clojure executable — prebuilt or built from source.",
            allow_files = True,
            cfg = "exec",
            executable = True,
            mandatory = True,
        ),
    },
)
