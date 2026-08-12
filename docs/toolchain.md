<!-- Generated with Stardoc: http://skydoc.bazel.build -->

A toolchain carrying the protoc-gen-clojure executable.

Why a toolchain rather than a plain label: the plugin is a build tool that must
be selected per host platform, and consumers should get a prebuilt native binary
rather than compiling Clojure. Toolchain resolution is Bazel's mechanism for
exactly that, and it keeps `clojure_proto_library` free of any per-platform
select().

<a id="protoc_gen_clojure_toolchain"></a>

## protoc_gen_clojure_toolchain

<pre>
load("@protoc_gen_clojure//clojure:toolchain.bzl", "protoc_gen_clojure_toolchain")

protoc_gen_clojure_toolchain(<a href="#protoc_gen_clojure_toolchain-name">name</a>, <a href="#protoc_gen_clojure_toolchain-plugin">plugin</a>)
</pre>

Declares a protoc-gen-clojure binary as a toolchain.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :------------- | :------------- | :------------- | :------------- | :------------- |
| <a id="protoc_gen_clojure_toolchain-name"></a>name |  A unique name for this target.   | <a href="https://bazel.build/concepts/labels#target-names">Name</a> | required |  |
| <a id="protoc_gen_clojure_toolchain-plugin"></a>plugin |  The protoc-gen-clojure executable — prebuilt or built from source.   | <a href="https://bazel.build/concepts/labels">Label</a> | required |  |


<a id="ProtocGenClojureInfo"></a>

## ProtocGenClojureInfo

<pre>
load("@protoc_gen_clojure//clojure:toolchain.bzl", "ProtocGenClojureInfo")

ProtocGenClojureInfo(<a href="#ProtocGenClojureInfo-plugin">plugin</a>, <a href="#ProtocGenClojureInfo-files">files</a>)
</pre>

The protoc-gen-clojure executable.

**FIELDS**

| Name  | Description |
| :------------- | :------------- |
| <a id="ProtocGenClojureInfo-plugin"></a>plugin |  File — the executable protoc invokes.    |
| <a id="ProtocGenClojureInfo-files"></a>files |  depset — runfiles needed to run it.    |


