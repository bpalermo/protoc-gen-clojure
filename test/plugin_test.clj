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
            DescriptorProtos$FeatureSet DescriptorProtos$FileOptions
            DescriptorProtos$MessageOptions DescriptorProtos$ServiceDescriptorProto
            UnknownFieldSet UnknownFieldSet$Field]
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

(deftest nested-types-are-emitted
  ;; Nested messages used to get no record (#2); the emitter named them in a
  ;; "NOT GENERATED" notice instead.
  ;;
  ;; golden/fixtures/nested/nested.clj is now the contract for what nested emission
  ;; looks like, produced by the real plugin over a real proto. What this keeps is the
  ;; part a fixture cannot state as sharply: that the "NOT GENERATED" notice is gone,
  ;; and that a map-entry type produces nothing — asserted here against a descriptor
  ;; built by hand, so it holds even for map entries protoc would refuse to compile.
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
                                           (.setMapEntry true)
                                           (.build)))))))
                (.build))
        out (plugin/emit-namespace fdp (constantly false) nil false)]
    (is (str/includes? out "(defrecord Outer-Inner [")
        "the nested type gets a record, named so it cannot collide with a message
         the schema could declare")
    (is (str/includes? out "(rt/message file-descriptor \"Outer.Inner\"")
        "and is looked up by its dotted proto path, the only spelling the runtime
         can resolve a nested type from")
    (is (str/includes? out "(defn Outer-Inner->proto")
        "with the conversion functions, which are the point of the record")
    (is (str/includes? out "(defn proto->Outer-Inner"))
    (is (not (str/includes? out "NOT GENERATED"))
        "the notice describing the gap must go with the gap")
    (is (not (str/includes? out "CountsEntry"))
        "a synthetic map entry gets no record — protobuf's own gencode emits no
         class for one either")))

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

;; ---------------------------------------------------------------------------
;; java class names for the prototype hint

(defn- nest-in-file-class-features
  "A FeatureSet carrying (pb.java).nest_in_file_class, built as protoc sends it: an
  unknown extension field, because nothing here registers JavaFeaturesProto. See the
  long note in plugin.clj for why the plugin reads it this way."
  ^DescriptorProtos$FeatureSet [yes?]
  (let [inner (-> (UnknownFieldSet/newBuilder)
                  (.addField 5 (-> (UnknownFieldSet$Field/newBuilder)
                                   (.addVarint (if yes? 2 1))
                                   (.build)))
                  (.build))
        outer (-> (UnknownFieldSet/newBuilder)
                  (.addField 1001 (-> (UnknownFieldSet$Field/newBuilder)
                                      (.addLengthDelimited (.toByteString inner))
                                      (.build)))
                  (.build))]
    (-> (DescriptorProtos$FeatureSet/newBuilder)
        (.setUnknownFields outer)
        (.build))))

(defn- fdp
  "A FileDescriptorProto with just enough set to exercise the naming rules."
  ^DescriptorProtos$FileDescriptorProto
  [{:keys [package java-package multiple-files? edition file-name nest-in-file-class]}]
  (let [opts (cond-> (DescriptorProtos$FileOptions/newBuilder)
               java-package    (.setJavaPackage java-package)
               multiple-files? (.setJavaMultipleFiles true)
               (some? nest-in-file-class)
               (.setFeatures (nest-in-file-class-features nest-in-file-class))
               :always         (.build))
        b    (cond-> (DescriptorProtos$FileDescriptorProto/newBuilder)
               package   (.setPackage package)
               file-name (.setName file-name)
               :always   (.setOptions opts))]
    (if edition
      (-> b (.setSyntax "editions") (.setEdition edition) (.build))
      (-> b (.setSyntax "proto3") (.build)))))

(defn- msg-with-nest
  "A DescriptorProto with (pb.java).nest_in_file_class set."
  ^DescriptorProtos$DescriptorProto [name yes?]
  (-> (DescriptorProtos$DescriptorProto/newBuilder)
      (.setName name)
      (.setOptions (-> (DescriptorProtos$MessageOptions/newBuilder)
                       (.setFeatures (nest-in-file-class-features yes?))
                       (.build)))
      (.build)))

(defn- plain-msg ^DescriptorProtos$DescriptorProto [name]
  (-> (DescriptorProtos$DescriptorProto/newBuilder) (.setName name) (.build)))

(deftest java-class-name-only-when-provable
  (testing "java_multiple_files puts every message at the top level"
    (is (= "com.acme.Tiny"
           (#'plugin/java-class-name (fdp {:package "acme" :java-package "com.acme"
                                           :multiple-files? true})
                                     (plain-msg "Tiny") ["Tiny"]))))

  (testing "edition 2024 does too, without java_multiple_files: nest_in_file_class
            defaults to NO, which is why bench/shapes.proto emits top-level classes
            beside a ShapesProto file class"
    (is (= "com.acme.Tiny"
           (#'plugin/java-class-name (fdp {:package "acme" :java-package "com.acme"
                                           :edition DescriptorProtos$Edition/EDITION_2024})
                                     (plain-msg "Tiny") ["Tiny"]))))

  (testing "no java_package falls back to the proto package"
    (is (= "acme.Tiny"
           (#'plugin/java-class-name (fdp {:package "acme" :multiple-files? true})
                                     (plain-msg "Tiny") ["Tiny"]))))

  (testing "NO hint for proto3 without java_multiple_files — the message is nested in
            the file class, whose name needs rules this deliberately does not
            implement. Emitting a guess here is what would produce wrong names."
    (is (nil? (#'plugin/java-class-name (fdp {:package "acme" :java-package "com.acme"})
                                        (plain-msg "Tiny") ["Tiny"]))))

  (testing "nor for edition 2023 without java_multiple_files, for the same reason"
    (is (nil? (#'plugin/java-class-name
               (fdp {:package "acme" :java-package "com.acme"
                     :edition DescriptorProtos$Edition/EDITION_2023})
               (plain-msg "Tiny") ["Tiny"]))))

  (testing "nor when there is no package at all to qualify the class with"
    (is (nil? (#'plugin/java-class-name (fdp {:multiple-files? true})
                                        (plain-msg "Tiny") ["Tiny"])))))

(deftest nest-in-file-class-numbers-match-protobuf
  (testing "the three wire constants plugin.clj hardcodes still match the real
            generated classes. It reads the feature off unknown fields to keep
            JavaFeaturesProto out of the native image, which means nothing else would
            notice a renumbering — so this asserts it against protobuf itself.

            Reached reflectively and only here: naming the class at compile time is
            exactly what plugin.clj must avoid, and a test may pay a cost the plugin
            cannot."
    ;; Build a descriptor first: initialising JavaFeaturesProto before protobuf's
    ;; edition-defaults cache is warm re-enters ExtensionRegistry.add and throws.
    @plugin/max-supported-edition
    (let [enum-cls (Class/forName
                    (str "com.google.protobuf.JavaFeaturesProto$JavaFeatures"
                         "$NestInFileClassFeature$NestInFileClass"))
          enum-num (fn [n] (.getNumber ^com.google.protobuf.ProtocolMessageEnum
                                       (clojure.lang.Reflector/invokeStaticMethod
                                        enum-cls "valueOf" (into-array Object [n]))))
          jf-desc  (clojure.lang.Reflector/invokeStaticMethod
                    (Class/forName "com.google.protobuf.JavaFeaturesProto$JavaFeatures")
                    "getDescriptor" (into-array Object []))
          ext      (clojure.lang.Reflector/getStaticField
                    "com.google.protobuf.JavaFeaturesProto" "java_")]
      (is (= @#'plugin/pb-java-extension-field
             (.getNumber (.getDescriptor
                          ^com.google.protobuf.GeneratedMessage$GeneratedExtension ext)))
          "(pb.java) extension field number moved")
      (is (= @#'plugin/nest-in-file-class-field
             (.getNumber (.findFieldByName ^com.google.protobuf.Descriptors$Descriptor jf-desc
                                           "nest_in_file_class")))
          "nest_in_file_class field number moved")
      (is (= {(enum-num "YES") :yes, (enum-num "NO") :no}
             @#'plugin/nest-in-file-class-values)
          "nest_in_file_class enum numbers moved"))))

(deftest nest-in-file-class-moves-the-message-inside-the-file-class
  (testing "a message setting nest_in_file_class = YES is spelled
            <pkg>.<FileClass>$<Message>, because that is where protobuf's Java
            plugin puts it. Emitting the top-level name instead is a name that
            does not exist, and rt/message then keeps its DynamicMessage."
    (is (= "com.acme.KitchenProto$NestedInFileClass"
           (#'plugin/java-class-name
            (fdp {:package "acme" :java-package "com.acme" :file-name "fixtures/kitchen.proto"
                  :edition DescriptorProtos$Edition/EDITION_2024})
            (msg-with-nest "NestedInFileClass" true)
            ["NestedInFileClass"]))))

  (testing "set on the FILE, every message in it nests"
    (is (= "com.acme.ShapesProto$Tiny"
           (#'plugin/java-class-name
            (fdp {:package "acme" :java-package "com.acme" :file-name "fixtures/bench/shapes.proto"
                  :edition DescriptorProtos$Edition/EDITION_2024
                  :nest-in-file-class true})
            (plain-msg "Tiny") ["Tiny"]))))

  (testing "a message setting NO overrides a file setting YES"
    (is (= "com.acme.Tiny"
           (#'plugin/java-class-name
            (fdp {:package "acme" :java-package "com.acme" :file-name "shapes.proto"
                  :edition DescriptorProtos$Edition/EDITION_2024
                  :nest-in-file-class true})
            (msg-with-nest "Tiny" false) ["Tiny"]))))

  (testing "nesting inside a message composes with nesting inside the file class"
    (is (= "com.acme.KitchenProto$Outer$Inner"
           (#'plugin/java-class-name
            (fdp {:package "acme" :java-package "com.acme" :file-name "kitchen.proto"
                  :edition DescriptorProtos$Edition/EDITION_2024
                  :nest-in-file-class true})
            (plain-msg "Inner") ["Outer" "Inner"]))))

  (testing "java_outer_classname wins over the derived name"
    (is (= "com.acme.Custom$Tiny"
           (#'plugin/java-class-name
            (-> (fdp {:package "acme" :java-package "com.acme" :file-name "shapes.proto"
                      :edition DescriptorProtos$Edition/EDITION_2024})
                (.toBuilder)
                (as-> b (.setOptions b (-> (.getOptions (.build b)) (.toBuilder)
                                           (.setJavaOuterClassname "Custom") (.build))))
                (.build))
            (msg-with-nest "Tiny" true) ["Tiny"]))))

  (testing "the derived file class is the camel-cased basename plus Proto, which is
            the edition 2024 default — shapes.proto -> ShapesProto even though no
            type is named Shapes, and underscores fold into camel case"
    (is (= "ShapesProto" (#'plugin/file-class-name
                          (fdp {:file-name "fixtures/bench/shapes.proto"
                                :edition DescriptorProtos$Edition/EDITION_2024}))))
    (is (= "LegacyStyleProto" (#'plugin/file-class-name
                               (fdp {:file-name "a/legacy_style.proto"
                                     :edition DescriptorProtos$Edition/EDITION_2024}))))))

;; ---------------------------------------------------------------------------
;; the message tree

(defn- msg
  "A DescriptorProto: `nested` are child messages, `map-entries` are the synthetic
  types a map<k,v> field produces (options.map_entry set)."
  ^DescriptorProtos$DescriptorProto
  [name & {:keys [nested map-entries]}]
  (let [b (DescriptorProtos$DescriptorProto/newBuilder)]
    (.setName b name)
    (doseq [n nested] (.addNestedType b ^DescriptorProtos$DescriptorProto n))
    (doseq [e map-entries]
      (.addNestedType b (-> (DescriptorProtos$DescriptorProto/newBuilder)
                            (.setName e)
                            (.setOptions (-> (DescriptorProtos$MessageOptions/newBuilder)
                                             (.setMapEntry true)
                                             (.build)))
                            (.build))))
    (.build b)))

(defn- map-entry-msg
  "A message with options.map_entry set, as protoc synthesises for map<k,v>."
  ^DescriptorProtos$DescriptorProto [name]
  (-> (DescriptorProtos$DescriptorProto/newBuilder)
      (.setName name)
      (.setOptions (-> (DescriptorProtos$MessageOptions/newBuilder)
                       (.setMapEntry true)
                       (.build)))
      (.build)))

(defn- file-with
  ^DescriptorProtos$FileDescriptorProto [& messages]
  (let [b (-> (DescriptorProtos$FileDescriptorProto/newBuilder)
              (.setName "t.proto")
              (.setPackage "acme")
              (.setSyntax "proto3")
              (.setOptions (-> (DescriptorProtos$FileOptions/newBuilder)
                               (.setJavaPackage "com.acme")
                               (.setJavaMultipleFiles true)
                               (.build))))]
    (doseq [m messages] (.addMessageType b ^DescriptorProtos$DescriptorProto m))
    (.build b)))

(deftest message-tree-recurses-and-names
  (let [tree (#'plugin/message-tree
              (file-with (msg "Outer"
                              :map-entries ["CountsEntry"]
                              :nested [(msg "Inner"
                                            :map-entries ["LabelsEntry"]
                                            :nested [(msg "Innermost")])])
                         (msg "Inner")))
        by-record (into {} (map (juxt :record-name identity)) tree)]

    (testing "every declared message appears, enclosing type before its children"
      (is (= ["Outer" "Outer-Inner" "Outer-Inner-Innermost" "Inner"]
             (mapv :record-name tree))))

    (testing "map entries are skipped at EVERY level, not just the root. A walk that
              only checks the top level emits a record for LabelsEntry — and the
              top-level-only walk this replaced never had to check at all, so the
              old behaviour was correct by accident"
      (is (empty? (filter #(str/includes? (:record-name %) "Entry") tree))))

    (testing "the runtime is asked for the dotted proto path, since that is the only
              thing it can resolve a nested type from"
      (is (= "Outer.Inner.Innermost" (:lookup-name (by-record "Outer-Inner-Innermost")))))

    (testing "java nesting is $ below the first segment"
      (is (= "com.acme.Outer$Inner$Innermost"
             (:java-class (by-record "Outer-Inner-Innermost")))))

    (testing "a nested type and a top-level type of the SAME proto name stay distinct
              — this is what `OuterInner`-style flattening would silently merge"
      (is (= "Outer.Inner" (:lookup-name (by-record "Outer-Inner"))))
      (is (= "Inner"       (:lookup-name (by-record "Inner"))))
      (is (not= (:java-class (by-record "Outer-Inner"))
                (:java-class (by-record "Inner")))))))

(deftest record-name-collision-is-refused
  (testing "defrecord munges - to _, so nested Outer.Inner and a top-level
            Outer_Inner both compile to class Outer_Inner and one silently clobbers
            the other. Emitting that is worse than failing, so it fails."
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (#'plugin/message-tree
                           (file-with (msg "Outer" :nested [(msg "Inner")])
                                      (msg "Outer_Inner")))))]
      (is (= "Outer_Inner" (:class (ex-data ex))))
      (testing "and the message names both culprits, since the fix is to rename one"
        (is (str/includes? (ex-message ex) "Outer.Inner"))
        (is (str/includes? (ex-message ex) "Outer_Inner")))))

  (testing "the check does not fire on names that merely look similar"
    (is (some? (#'plugin/message-tree
                (file-with (msg "Outer" :nested [(msg "Inner")])
                           (msg "OuterInner")))))))

(deftest map-entry-is-skipped-at-the-root-too
  (testing "protoc only ever synthesises map-entry types as NESTED types, and rejects
            `option map_entry = true` written by hand. But --descriptor_set_in takes a
            set from anywhere, so the root is filtered as well — a filter that held
            only below the root would emit a record for a synthetic type on the one
            input class that can carry one."
    (is (= ["Real"]
           (mapv :record-name
                 (#'plugin/message-tree
                  (file-with (map-entry-msg "TopLevelEntry")
                             (msg "Real"))))))))
