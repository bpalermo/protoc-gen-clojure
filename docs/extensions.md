<!-- Generated with Stardoc: http://skydoc.bazel.build -->

Module extension that fetches the prebuilt protoc-gen-clojure binary.

This is what makes the module cheap to depend on: a consumer gets a native
executable for its host platform and needs no Clojure, no JVM and no GraalVM to
generate a .clj file.

The repos come in two layers. One repo per platform holds the downloaded binary
and its toolchain implementation; a single hub repo declares the `toolchain()`
targets that point at them. The split is what makes
`register_toolchains("@protoc_gen_clojure_toolchains//:all")` resolve to every
platform — a `:all` pattern only sees one package, so the toolchain declarations
have to live together in that one package.

<a id="toolchains"></a>

## toolchains

<pre>
toolchains = use_extension("@protoc_gen_clojure//clojure:extensions.bzl", "toolchains")
</pre>

Fetches prebuilt protoc-gen-clojure binaries and exposes them as toolchains.



