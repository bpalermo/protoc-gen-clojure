"""clojure_proto_library — generate Clojure from .proto via protoc-gen-clojure.

It exists as a real Starlark rule rather than a genrule for a specific reason:
protoc needs every transitive dependency of the .proto files resolvable, and
there are only two ways to give it them —

  --proto_path   requires the well-known types in the sandbox. Reachable, but
                 edition 2024's `import option java_features.proto` is not:
                 protobuf exports that file in no filegroup.
  --descriptor_set_in  needs the TRANSITIVE descriptor sets. proto_library
                 emits its own set without imports, so a single set is not
                 enough.

The transitive sets live on ProtoInfo, which a genrule cannot reach. Hence a
rule.
"""

# Bazel 9 removed ProtoInfo from the Starlark globals, exactly as it did
# JavaInfo and CcInfo.
load("@protobuf//bazel/common:proto_info.bzl", "ProtoInfo")

TOOLCHAIN_TYPE = "@protoc_gen_clojure//bazel:toolchain_type"

# protobuf's own toolchain type for protoc, and the only route to a PREBUILT
# protoc. This rule used to depend on `@protobuf//:protoc` directly, which is a
# plain cc_binary: that meant compiling protoc from C++ source — ~70 translation
# units — to run a tool upstream already publishes prebuilt for every platform we
# support, and no protobuf flag can redirect a cc_binary label.
#
# Resolving the toolchain instead makes the choice the build's, via
# --@protobuf//bazel/toolchains:prefer_prebuilt_protoc. The toolchain resolves
# either way and carries the source-built protoc when that flag is off, so this is
# not an opt-in path with a fallback: it is the only path. Verified by temporarily
# failing in a fallback branch and confirming no flag combination reached it.
#
# The label sits under bazel/private but is declared `//visibility:public`. That is
# the supported entry point today — protobuf's own rules resolve the same type
# through bazel/private/toolchain_helpers.bzl — but the package name is fair warning
# that it may move, so a protobuf upgrade should re-check it.
PROTO_TOOLCHAIN_TYPE = "@protobuf//bazel/private:proto_toolchain_type"

def _plugin(ctx):
    """The plugin executable: an explicit `plugin` attr wins, else the toolchain.

    The attribute exists so this repo can test the binary it just built, and so
    a consumer can substitute one. Everyone else gets the prebuilt native binary
    through toolchain resolution and needs no Clojure toolchain at all.
    """
    if ctx.attr.plugin:
        # Runfiles matter here: an override is typically a launcher script (a
        # clojure_binary wrapping java_binary), which is useless in the sandbox
        # without the jar and JVM it points at.
        return ctx.executable.plugin, depset(
            [ctx.executable.plugin],
            transitive = [ctx.attr.plugin[DefaultInfo].default_runfiles.files],
        )
    toolchain = ctx.toolchains[TOOLCHAIN_TYPE]
    if not toolchain:
        fail(
            "no protoc-gen-clojure toolchain is registered. Either register one:\n" +
            '    plugin = use_extension("@protoc_gen_clojure//bazel:extensions.bzl", "toolchains")\n' +
            '    use_repo(plugin, "protoc_gen_clojure_toolchains")\n' +
            '    register_toolchains("@protoc_gen_clojure_toolchains//:all")\n' +
            "or pass an executable explicitly via this rule's `plugin` attribute.",
        )
    info = toolchain.protoc_gen_clojure
    return info.plugin, info.files

def _clojure_proto_library_impl(ctx):
    proto_info = ctx.attr.proto[ProtoInfo]
    plugin, plugin_files = _plugin(ctx)

    # A FilesToRunProvider, so it carries protoc's own runfiles.
    protoc = ctx.toolchains[PROTO_TOOLCHAIN_TYPE].proto.proto_compiler

    outs = ctx.outputs.outs
    if not outs:
        fail("clojure_proto_library requires `outs`")

    # protoc writes paths relative to --clojure_out, and predeclared outputs land
    # under the package's output dir. Recover that root by stripping the declared
    # relative name off the first output's full path.
    first_rel = ctx.attr.outs[0].name
    out_root = outs[0].path[:-len(first_rel)].rstrip("/")

    # Names as protoc sees them: relative to the proto source root, which is a
    # _virtual_imports directory when strip_import_prefix is used.
    root = proto_info.proto_source_root
    names = []
    for f in proto_info.direct_sources:
        p = f.path
        if root and p.startswith(root + "/"):
            p = p[len(root) + 1:]
        names.append(p)

    args = ctx.actions.args()
    args.add("--plugin=protoc-gen-clojure=" + plugin.path)
    args.add_joined(
        "--descriptor_set_in",
        proto_info.transitive_descriptor_sets,
        join_with = ":",
        format_joined = "%s",
    )
    if ctx.attr.options:
        args.add("--clojure_opt=" + ",".join([
            "%s=%s" % (k, v)
            for k, v in ctx.attr.options.items()
        ]))
    args.add("--clojure_out=" + out_root)
    args.add_all(names)

    ctx.actions.run(
        arguments = [args],
        executable = protoc,
        inputs = depset(transitive = [proto_info.transitive_descriptor_sets]),
        mnemonic = "ClojureProtoGen",
        outputs = outs,
        progress_message = "Generating Clojure for %{label}",
        # protoc is not listed here: passing a FilesToRunProvider as `executable`
        # already contributes the binary and its runfiles. Only the plugin needs
        # declaring, and it needs its runfiles too — see _plugin.
        tools = plugin_files,
    )

    return [DefaultInfo(files = depset(outs))]

clojure_proto_library = rule(
    implementation = _clojure_proto_library_impl,
    doc = "Generate Clojure sources from a proto_library using protoc-gen-clojure.",
    toolchains = [
        config_common.toolchain_type(TOOLCHAIN_TYPE, mandatory = False),
        # Mandatory, unlike the plugin toolchain above: protobuf registers this one
        # itself, so there is nothing for a consumer to set up, and Bazel's own
        # "no matching toolchains" error names the type more clearly than a
        # hand-written fallback could.
        config_common.toolchain_type(PROTO_TOOLCHAIN_TYPE, mandatory = True),
    ],
    attrs = {
        "proto": attr.label(
            doc = "The proto_library to generate from.",
            mandatory = True,
            providers = [ProtoInfo],
        ),
        # output_list, not string_list: it makes each generated file an
        # addressable label, which is what lets other rules consume them
        # individually — //test:update_golden diffs them against goldens one by
        # one. The user-facing syntax is unchanged.
        "outs": attr.output_list(
            doc = "Generated .clj paths, relative to this package.",
            mandatory = True,
        ),
        "options": attr.string_dict(
            doc = "Plugin parameters, e.g. {\"ns_prefix\": \"corp\"}.",
        ),
        "plugin": attr.label(
            doc = "Override the protoc-gen-clojure executable. Defaults to the " +
                  "registered toolchain's prebuilt binary.",
            cfg = "exec",
            executable = True,
        ),
    },
)

def _proto_transitive_descriptor_sets_impl(ctx):
    """Expose a proto_library's TRANSITIVE descriptor sets as ordinary files.

    proto_library's default output is its OWN descriptor set, without imports,
    which protoc cannot resolve on its own. The transitive sets are reachable
    only through ProtoInfo, so anything driving protoc by hand needs this to get
    at them. Same constraint that forced clojure_proto_library to be a rule; see
    the module docstring.
    """
    return [DefaultInfo(
        files = ctx.attr.proto[ProtoInfo].transitive_descriptor_sets,
    )]

proto_transitive_descriptor_sets = rule(
    implementation = _proto_transitive_descriptor_sets_impl,
    doc = "The transitive descriptor sets of a proto_library, as files.",
    attrs = {
        "proto": attr.label(mandatory = True, providers = [ProtoInfo]),
    },
)
