<!-- Generated with Stardoc: http://skydoc.bazel.build -->

clojure_proto_library — generate Clojure from .proto via protoc-gen-clojure.

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

<a id="clojure_proto_library"></a>

## clojure_proto_library

<pre>
load("@protoc_gen_clojure//clojure:defs.bzl", "clojure_proto_library")

clojure_proto_library(<a href="#clojure_proto_library-name">name</a>, <a href="#clojure_proto_library-outs">outs</a>, <a href="#clojure_proto_library-options">options</a>, <a href="#clojure_proto_library-plugin">plugin</a>, <a href="#clojure_proto_library-proto">proto</a>)
</pre>

Generate Clojure sources from a proto_library using protoc-gen-clojure.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :------------- | :------------- | :------------- | :------------- | :------------- |
| <a id="clojure_proto_library-name"></a>name |  A unique name for this target.   | <a href="https://bazel.build/concepts/labels#target-names">Name</a> | required |  |
| <a id="clojure_proto_library-outs"></a>outs |  Generated .clj paths, relative to this package.   | List of labels; <a href="https://bazel.build/reference/be/common-definitions#configurable-attributes">nonconfigurable</a> | required |  |
| <a id="clojure_proto_library-options"></a>options |  Plugin parameters, e.g. {"ns_prefix": "corp"}.   | <a href="https://bazel.build/rules/lib/core/dict">Dictionary: String -> String</a> | optional |  `{}`  |
| <a id="clojure_proto_library-plugin"></a>plugin |  Override the protoc-gen-clojure executable. Defaults to the registered toolchain's prebuilt binary.   | <a href="https://bazel.build/concepts/labels">Label</a> | optional |  `None`  |
| <a id="clojure_proto_library-proto"></a>proto |  The proto_library to generate from.   | <a href="https://bazel.build/concepts/labels">Label</a> | required |  |


<a id="proto_transitive_descriptor_sets"></a>

## proto_transitive_descriptor_sets

<pre>
load("@protoc_gen_clojure//clojure:defs.bzl", "proto_transitive_descriptor_sets")

proto_transitive_descriptor_sets(<a href="#proto_transitive_descriptor_sets-name">name</a>, <a href="#proto_transitive_descriptor_sets-proto">proto</a>)
</pre>

The transitive descriptor sets of a proto_library, as files.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :------------- | :------------- | :------------- | :------------- | :------------- |
| <a id="proto_transitive_descriptor_sets-name"></a>name |  A unique name for this target.   | <a href="https://bazel.build/concepts/labels#target-names">Name</a> | required |  |
| <a id="proto_transitive_descriptor_sets-proto"></a>proto |  -   | <a href="https://bazel.build/concepts/labels">Label</a> | required |  |


