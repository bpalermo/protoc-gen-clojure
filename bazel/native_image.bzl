"""A minimal native-image rule.

Making the native binary a Bazel artifact is the keystone for several things: //test:distribution_test is now a real
`bazel test` with the binary in `data`, the shell scripts that resolved GraalVM
and put tools on PATH are gone, and an OCI image rule can consume the binary from
the graph (issue #3).

Verified on macOS and on Linux CI.

Why not rules_graalvm's own native_image rule: on macOS it routes through
apple_support.run, which puts SDKROOT into the action environment, and Bazel's
XcodeLocalEnvProvider then injects SDKROOT again — Bazel crashes outright with
"Multiple entries with same key: SDKROOT". This rule deliberately does not touch
apple_support.

The cost is stated plainly rather than hidden: the action is NOT hermetic. It runs
unsandboxed with the ambient environment, because native-image shells out to the
platform toolchain (clang/ld on macOS, gcc on Linux) to link the final binary, and
Bazel's scrubbed environment leaves it unable to find one. apple_support exists
precisely to paper over this; the trade here is a non-hermetic action instead of an
unusable rule.
"""

def _native_image_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.binary_name)

    args = ctx.actions.args()
    args.add_all(ctx.attr.extra_args)
    args.add("-jar", ctx.file.jar)

    # native-image takes the output as a path, and the action's cwd is the
    # execroot, so out.path lands it where Bazel expects the declared output.
    args.add(out.path)

    ctx.actions.run(
        executable = ctx.file._launcher,
        arguments = [args],
        inputs = depset(
            [ctx.file.jar, ctx.file._launcher],
            transitive = [ctx.attr._graalvm[DefaultInfo].files],
        ),
        outputs = [out],
        mnemonic = "NativeImage",
        progress_message = "Building native image %{output}",
        # See the module docstring: the linker needs the ambient toolchain.
        execution_requirements = {
            "local": "1",
            "no-sandbox": "1",
            "no-remote": "1",
        },
        use_default_shell_env = True,
    )

    return [DefaultInfo(
        executable = out,
        files = depset([out]),
    )]

native_image = rule(
    implementation = _native_image_impl,
    doc = "Build a GraalVM native image from a deploy jar.",
    executable = True,
    attrs = {
        "jar": attr.label(
            doc = "The deploy jar to compile. Must carry a Main-Class.",
            allow_single_file = [".jar"],
            mandatory = True,
        ),
        "binary_name": attr.string(
            doc = "Name of the produced executable.",
            mandatory = True,
        ),
        "extra_args": attr.string_list(
            doc = "Flags for native-image.",
            default = [],
        ),
        "_launcher": attr.label(
            default = Label("@graalvm//:bin-native-image"),
            allow_single_file = True,
            cfg = "exec",
        ),
        # The whole GraalVM tree. The launcher alone is not enough: it is a
        # symlink into lib/svm/bin and resolves its JAVA_HOME relative to its own
        # location, so the SDK has to be present as inputs.
        "_graalvm": attr.label(
            default = Label("@graalvm//:files"),
            cfg = "exec",
        ),
    },
)
