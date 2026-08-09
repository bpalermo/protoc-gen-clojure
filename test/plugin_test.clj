(ns plugin-test
  "The plugin protocol and the emitter.

  Scoped to what needs only protobuf-java: the plugin protocol and naming.
  Emission is pinned separately, by the golden files under //test:golden_test.

  Whether the *emitted code works* — round-trips on the wire, serves real RPCs,
  stays byte-identical to protoc's Java backend — is asserted by the runtime
  library the generated code requires, which owns those tests. Keeping them there
  is what lets this module depend on nothing but protobuf-java."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protoc-gen-clojure.plugin :as plugin])
  (:import [com.google.protobuf DescriptorProtos$Edition
            DescriptorProtos$DescriptorProto DescriptorProtos$FileDescriptorProto
            DescriptorProtos$MessageOptions DescriptorProtos$ServiceDescriptorProto]
           [com.google.protobuf.compiler PluginProtos$CodeGeneratorRequest
            PluginProtos$CodeGeneratorResponse$Feature]))

(deftest editions-window
  (testing "we advertise an edition we can actually resolve, not one the enum
            merely lists — EDITION_2026 exists in protobuf-java 4.35.1 but is
            not resolvable, so it must not be advertised"
    (is (= DescriptorProtos$Edition/EDITION_2024 @plugin/max-supported-edition)))

  (testing "the probe is capability-based, so it stays honest as protobuf-java moves"
    (is (true?  (#'plugin/resolvable-edition? DescriptorProtos$Edition/EDITION_2023)))
    (is (true?  (#'plugin/resolvable-edition? DescriptorProtos$Edition/EDITION_2024)))
    (is (false? (#'plugin/resolvable-edition? DescriptorProtos$Edition/EDITION_2026)))))

(deftest response-declares-editions-support
  (let [resp  (plugin/generate (PluginProtos$CodeGeneratorRequest/getDefaultInstance))
        flags (.getSupportedFeatures resp)]
    (testing "FEATURE_SUPPORTS_EDITIONS is set — without it protoc rejects every
              editions file before the plugin is even consulted"
      (is (pos? (bit-and flags (.getNumber PluginProtos$CodeGeneratorResponse$Feature/FEATURE_SUPPORTS_EDITIONS)))))
    (testing "and proto3 optional, so proto3 files still work"
      (is (pos? (bit-and flags (.getNumber PluginProtos$CodeGeneratorResponse$Feature/FEATURE_PROTO3_OPTIONAL)))))
    (testing "with a declared window"
      (is (= (.getNumber DescriptorProtos$Edition/EDITION_PROTO2) (.getMinimumEdition resp)))
      (is (= (.getNumber DescriptorProtos$Edition/EDITION_2024) (.getMaximumEdition resp))))))

(deftest boolean-parameters
  ;; `key=false` has to mean false. `boolean` on the parsed value gets this
  ;; backwards — every non-empty string is truthy — so `keep_source_info=false`
  ;; would switch the option on. Bazel's string_dict options arrive as strings,
  ;; which makes that the natural spelling and the inversion easy to ship.
  (testing "a bare key is true"
    (is (true? (#'plugin/flag? true))))
  (testing "explicit falsehoods are false, in every spelling"
    (doseq [v ["false" "FALSE" "0" "no" "off" ""]]
      (is (false? (#'plugin/flag? v)) (str v " should be false"))))
  (testing "anything else is true"
    (doseq [v ["true" "1" "yes"]]
      (is (true? (#'plugin/flag? v)) (str v " should be true"))))
  (testing "an absent key is false"
    (is (false? (#'plugin/flag? nil)))))

(deftest dependency-classification
  ;; A dep is a well-known type by PATH, not by absence from file_to_generate.
  ;; protoc lists transitive imports in proto_file while naming only the
  ;; requested files in file_to_generate, so keying off the latter classifies
  ;; every custom transitive import as well-known and emits an rt/known-file call
  ;; that cannot resolve.
  (let [never-generated (constantly false)]
    (testing "google/protobuf/* comes from protobuf-java"
      (is (= {:form "(rt/known-file \"google/protobuf/timestamp.proto\")"}
             (#'plugin/dep-form "google/protobuf/timestamp.proto" never-generated nil))))

    (testing "a custom import resolves through its namespace even when it is not
              in file_to_generate — partial generation must still work"
      (is (= {:require "acme.greeter.greeter"
              :form    "acme.greeter.greeter/file-descriptor"}
             (#'plugin/dep-form "acme/greeter/greeter.proto" never-generated nil))))

    (testing "google/rpc and google/api are not well-known here; protobuf-java
              does not carry them"
      (is (contains? (#'plugin/dep-form "google/rpc/status.proto" never-generated nil)
                     :require)))))

(deftest large-descriptors-stay-compilable
  ;; A class file's CONSTANT_Utf8 caps at 65535 bytes and base64 is ASCII, so a big
  ;; descriptor emitted as one literal cannot be AOT-compiled. Loading from source
  ;; does not hit it, so nothing here would notice without this.
  (testing "a short string emits as an ordinary literal"
    (is (= "\"abc\"" (#'plugin/literal "abc"))))

  (testing "a string over the ceiling is split and joined with str"
    (let [big (apply str (repeat 70000 "A"))
          out (#'plugin/literal big)]
      (is (str/starts-with? out "(str "))
      (is (> (count (re-seq #"\"A+\"" out)) 1) "more than one literal")
      (doseq [lit (re-seq #"\"(A+)\"" out)]
        (is (<= (count (second lit)) 65535)
            "every emitted literal must fit the constant pool"))))

  (testing "chunking is lossless"
    (let [big (apply str (map #(nth "ABCDEFGH" (mod % 8)) (range 100000)))]
      (is (= big (eval (read-string (#'plugin/literal big))))))))

(deftest nested-types-are-declared-not-silently-dropped
  ;; Nested messages get no record yet (#2). The failure mode to avoid is silence:
  ;; the file generates fine and `->Inner` simply does not exist. The emitter names
  ;; them in the output instead. Map entries must NOT be listed — map<k,v>
  ;; synthesises a nested *Entry message that is an encoding detail.
  (let [fdp (-> (DescriptorProtos$FileDescriptorProto/newBuilder)
                (.setName "demo/nest.proto")
                (.addMessageType
                 (-> (DescriptorProtos$DescriptorProto/newBuilder)
                     (.setName "Outer")
                     (.addNestedType
                      (-> (DescriptorProtos$DescriptorProto/newBuilder)
                          (.setName "Inner")))
                     (.addNestedType
                      (-> (DescriptorProtos$DescriptorProto/newBuilder)
                          (.setName "CountsEntry")
                          (.setOptions (-> (DescriptorProtos$MessageOptions/newBuilder)
                                           (.setMapEntry true)))))))
                (.build))
        out (plugin/emit-namespace fdp (constantly false) nil false)]
    (is (str/includes? out "NOT GENERATED") "the gap is stated in the output")
    (is (str/includes? out "Outer.Inner") "the nested type is named")
    (is (not (str/includes? out "CountsEntry"))
        "a synthetic map entry must not be reported as a missing type")))

(deftest runtime-namespaces-are-configurable
  ;; The requires this emits are the real public API — they are written into every
  ;; generated file, so changing them after a release breaks any consumer with
  ;; generated code checked in. Configurable means that commitment is reversible.
  (let [fdp (-> (DescriptorProtos$FileDescriptorProto/newBuilder)
                (.setName "demo/thing.proto")
                (.addMessageType (-> (DescriptorProtos$DescriptorProto/newBuilder)
                                     (.setName "Thing")))
                (.addService (-> (DescriptorProtos$ServiceDescriptorProto/newBuilder)
                                 (.setName "Doer")))
                (.build))]
    (testing "defaults are the runtime library's current namespaces"
      (let [out (plugin/emit-namespace fdp (constantly false) nil false)]
        (is (str/includes? out "[clj-grpc.codec :as codec]"))
        (is (str/includes? out "[clj-grpc.runtime :as rt]"))
        (is (str/includes? out "[clj-grpc.runtime.service :as rts]"))))

    (testing "each can be overridden independently"
      (let [out (plugin/emit-namespace fdp (constantly false) nil false
                                       {:codec   "acme.pb.codec"
                                        :runtime "acme.pb.runtime"
                                        :service "acme.rpc.service"})]
        (is (str/includes? out "[acme.pb.codec :as codec]"))
        (is (str/includes? out "[acme.pb.runtime :as rt]"))
        (is (str/includes? out "[acme.rpc.service :as rts]"))
        (is (not (str/includes? out "clj-grpc")) "no default leaks through")))

    (testing "resolution falls back per key, so a partial override is safe"
      (is (= (assoc plugin/default-runtime-namespaces :codec "only.this")
             (#'plugin/runtime-namespaces {:codec_ns "only.this"}))))

    (testing "a message-only file never requires the service namespace, since that
              is the one that pulls grpc-java"
      (let [msgs-only (-> (DescriptorProtos$FileDescriptorProto/newBuilder)
                          (.setName "demo/m.proto")
                          (.addMessageType (-> (DescriptorProtos$DescriptorProto/newBuilder)
                                               (.setName "M")))
                          (.build))
            out (plugin/emit-namespace msgs-only (constantly false) nil false)]
        (is (not (str/includes? out "rts")))))))

(deftest naming
  (is (= "acme.greeter.greeter" (plugin/proto->ns "acme/greeter/greeter.proto" nil)))
  (is (= "corp.acme.greeter.greeter" (plugin/proto->ns "acme/greeter/greeter.proto" "corp")))
  (testing "underscores become hyphens in the ns and come back in the path"
    (is (= "fixtures.e2024legacy.legacy-style"
           (plugin/proto->ns "fixtures/e2024legacy/legacy_style.proto" nil)))
    (is (= "fixtures/e2024legacy/legacy_style.clj"
           (plugin/ns->path "fixtures.e2024legacy.legacy-style")))))
