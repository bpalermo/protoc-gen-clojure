(ns protoc-gen-clojure.plugin
  "protoc-gen-clojure — a protoc/buf codegen plugin that emits Clojure.

  Lets a Clojure project be an ordinary entry in buf.gen.yaml alongside
  protocolbuffers/go, protocolbuffers/java and the rest, instead of requiring
  hand-written Java interop to reach generated stubs.

  Emitted files require clj-grpc.codec and clj-grpc.runtime — the runtime this
  generates code FOR, which is a separate artifact. The generator is named after
  itself rather than after that runtime, mirroring protoc-gen-go, whose own
  package is cmd/protoc-gen-go and not the runtime package it emits imports for.
  Users of protoc or buf need neither the runtime nor a JVM.

  EDITIONS POLICY — the thing that sinks most third-party plugins:

  - We advertise FEATURE_SUPPORTS_EDITIONS, so protoc will hand us editions
    files instead of rejecting the request.
  - We advertise `maximum_edition` PROBED from the linked protobuf-java rather
    than hardcoded — see `max-supported-edition`. Bumping the protobuf-java
    dependency raises it automatically. This is the failure mode that stranded
    the hardcoded `GetMaximumEdition()` in protoc's C++ plugins when edition
    2024 shipped.
  - We perform NO feature resolution ourselves. The emitted code embeds the
    FileDescriptorProto verbatim and lets protobuf-java's
    `FileDescriptor/buildFrom` resolve features at load time. So a future
    edition needs no codegen changes at all: it changes what protobuf-java
    resolves, not what we emit.

  Parameters (comma-separated, `protoc --clojure_out=key=value,key2=value2:DIR`):
    ns_prefix=foo          prefix every generated namespace with `foo.`
    keep_source_info=true  embed SourceCodeInfo (comments/spans) too; off by
                           default because it dominates the payload and is
                           useless at runtime
    codec_ns=…             namespace providing set-field!/get-field
    runtime_ns=…           namespace providing file-descriptor/message/field
    service_ns=…           namespace providing service/methods-map

  The three *_ns options exist because the requires this emits are the real
  public API: they are written into every generated file, so changing them later
  breaks any consumer with generated code checked in. Defaults match the runtime
  library today; overriding them lets a project move to a differently named
  runtime without waiting for this plugin to be re-released."
  (:require [clojure.string :as str])
  (:import [com.google.protobuf DescriptorProtos$Edition DescriptorProtos$FileDescriptorProto
            DescriptorProtos$DescriptorProto DescriptorProtos$FieldDescriptorProto
            DescriptorProtos$FeatureSet DescriptorProtos$ServiceDescriptorProto
            Descriptors$FileDescriptor
            ByteString UnknownFieldSet]
           [com.google.protobuf.compiler PluginProtos$CodeGeneratorRequest
            PluginProtos$CodeGeneratorResponse PluginProtos$CodeGeneratorResponse$Feature
            PluginProtos$CodeGeneratorResponse$File]
           [java.util Base64])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; editions window

(def ^:private sentinel-editions
  #{"EDITION_UNKNOWN" "EDITION_LEGACY" "EDITION_MAX" "EDITION_UNSTABLE"})

(defn- candidate-editions
  "Real editions, ascending. Excludes the sentinels (EDITION_MAX,
  EDITION_UNSTABLE) and the *_TEST_ONLY values, which exist for protobuf's own
  conformance suite and must never be advertised."
  []
  (->> (DescriptorProtos$Edition/values)
       (remove #(.contains (.name ^DescriptorProtos$Edition %) "TEST_ONLY"))
       (remove #(contains? sentinel-editions (.name ^DescriptorProtos$Edition %)))
       (remove #(neg? (.getNumber ^DescriptorProtos$Edition %)))
       (sort-by #(.getNumber ^DescriptorProtos$Edition %))))

(defn- resolvable-edition?
  "Can the linked protobuf-java actually build a descriptor at this edition?

  Enum membership is NOT evidence of support — protobuf-java 4.35.1 lists
  EDITION_2026 long before it can resolve its features. Advertising an edition
  we cannot resolve would make protoc hand us files we then fail on, which is
  worse than declining them up front. So probe the real thing: build a trivial
  FileDescriptorProto at that edition and see whether feature resolution
  succeeds."
  [^DescriptorProtos$Edition ed]
  (try
    (Descriptors$FileDescriptor/buildFrom
     (-> (DescriptorProtos$FileDescriptorProto/newBuilder)
         (.setName (str "protoc_gen_clojure_probe_" (.getNumber ed) ".proto"))
         (.setSyntax "editions")
         (.setEdition ed)
         (.build))
     (into-array Descriptors$FileDescriptor []))
    true
    (catch Throwable _ false)))

(def max-supported-edition
  "Highest edition the linked protobuf-java can actually resolve.

  Probed once, descending. Bumping the protobuf-java dependency raises this on
  its own — no constant to forget, which is exactly the bug that stranded the
  hardcoded GetMaximumEdition() in protoc's C++ plugins when edition 2024
  shipped."
  (delay
    (or (->> (candidate-editions)
             reverse
             (filter resolvable-edition?)
             first)
        DescriptorProtos$Edition/EDITION_2023)))

;; ---------------------------------------------------------------------------
;; naming

(defn- segment
  "One proto path segment -> one Clojure namespace segment. Underscores become
  hyphens; the file name on disk gets them back via Clojure's own munging."
  [s]
  (-> s (str/replace "_" "-")))

(defn proto->ns
  "acme/greeter/greeter.proto -> acme.greeter.greeter"
  [filename prefix]
  (let [base (-> filename
                 (str/replace #"\.proto$" "")
                 (str/split #"/"))]
    (str/join "." (concat (when (seq prefix) [prefix]) (map segment base)))))

(defn field-key-symbol
  "proto field name -> record field / map key. Kebab-cased, matching
  clj-grpc.codec's default :kebab naming so generated records and generic maps
  use the same keys and stay interchangeable."
  [s]
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace "_" "-")
      (str/lower-case)))

(defn ns->path [ns-name]
  (str (-> ns-name (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(defn- parse-params [^String param]
  (into {}
        (for [kv (str/split (or param "") #",")
              :when (seq kv)
              :let [[k v] (str/split kv #"=" 2)]]
          [(keyword k) (or v true)])))

(defn- flag?
  "A parameter value as a boolean.

  A bare `key` means true. `key=false` must mean FALSE — which `boolean` gets
  wrong, because every non-empty string is truthy in Clojure, so `=false` would
  switch the option ON. That matters more than it looks: Bazel's string_dict
  options always arrive as strings, so `{\"keep_source_info\": \"false\"}` is the
  natural way to write it and the naive reading inverts it."
  [v]
  (cond
    (nil? v)   false
    (true? v)  true
    :else      (not (contains? #{"false" "0" "no" "off" ""}
                               (str/lower-case (str v))))))

;; ---------------------------------------------------------------------------
;; java class names
;;
;; Encoding runs through the prototype's builder. A DynamicMessage builder stores
;; fields in a FieldSet map and boxes values; the generated Java builder has typed
;; fields. Measured in clj-protobuf's benchmark, handing rt/message the Java default
;; instance instead is ~45% faster to encode and ~46% lighter on allocation for small
;; messages, and beats protobuf's own generated-code arm on some shapes. Nothing else
;; changes — same codec, same field descriptors, same bytes.
;;
;; So this computes the Java class name and passes it along. rt/message resolves it
;; if the class is present AND describes the same message, and silently uses the
;; DynamicMessage otherwise, which is what makes a conservative implementation the
;; right one: a name we decline to compute costs speed, and a name we get wrong costs
;; speed too. Neither breaks anything.
;;
;; The file-level options answer where a message lands only when no per-message
;; feature overrides them, so both are consulted:
;;
;;   java_multiple_files = true          -> <pkg>.<Message>, always top level
;;   edition 2024 or later               -> <pkg>.<Message>; nest_in_file_class
;;                                          defaults to NO, so messages are top level
;;                                          even without java_multiple_files
;;   nest_in_file_class = YES            -> <pkg>.<FileClass>$<Message>, wherever the
;;                                          feature is set, on the message or the file
;;   anything else (proto2, proto3 or     -> no hint. The message is nested in the file
;;   edition 2023 without multiple_files)   class, and the pre-2024 outer-class rules
;;                                          (camel-cased basename, plus an OuterClass
;;                                          suffix on collision) are not implemented.
;;
;; Reading nest_in_file_class is NOT feature resolution: only an EXPLICITLY set value
;; is read, from the message and then the file, and the edition default (NO) is what
;; the absence of both already means. No defaults table, no inheritance walk, and
;; nothing here resolves a feature protobuf-java would resolve differently.
;;
;; The file class name is only needed for edition 2024 and later, because that is the
;; only place nest_in_file_class exists; earlier files reach here only with
;; java_multiple_files, where nothing nests. That is what keeps this short: from 2024
;; the default outer class is the camel-cased basename plus "Proto" unconditionally,
;; with no collision suffix to reproduce. Verified against the corpus, whose Java is
;; generated by protobuf's own plugin: shapes.proto -> ShapesProto with no colliding
;; type, kitchen.proto -> KitchenProto where a message IS named Kitchen. Pre-2024 the
;; suffix is conditional and different (e2023 kitchen -> KitchenOuterClass), which is
;; precisely the rule set this declines to implement.
;;
;; A name that cannot be derived is nil, and a hint is only ever a hint: rt/message
;; verifies the class describes the same message and keeps its DynamicMessage
;; otherwise. So being wrong costs the optimisation, never the bytes.
(def ^:private edition-2024-number
  (.getNumber DescriptorProtos$Edition/EDITION_2024))

(defn- top-level-java-class?
  [^DescriptorProtos$FileDescriptorProto fdp]
  (let [opts (.getOptions fdp)]
    (or (.getJavaMultipleFiles opts)
        (and (= "editions" (.getSyntax fdp))
             (>= (.getNumber (.getEdition fdp)) edition-2024-number)))))

;; (pb.java).nest_in_file_class is read off the UNKNOWN FIELDS of the FeatureSet,
;; not through the generated JavaFeaturesProto extension. That is a deliberate
;; retreat from the obvious implementation, and the reason is worth recording.
;;
;; The obvious version parses the request with an ExtensionRegistry carrying
;; JavaFeaturesProto and reads the typed extension. It works on the JVM and cannot be
;; made to work in the native image:
;;
;;   - JavaFeaturesProto's class initialiser builds the java_features.proto
;;     descriptor, which resolves edition defaults, which calls
;;     Descriptors.getJavaEditionDefaults, which adds JavaFeaturesProto.java_ to a
;;     registry — before that extension is internalInit'd. Being the first code to
;;     initialise the class therefore dies with "getDescriptor() called before
;;     internalInit()".
;;   - `--initialize-at-build-time` (no argument, see the BUILD file) initialises
;;     everything during image construction, so protobuf's own Descriptors pulls the
;;     class in and the failure happens at build time, leaving it erroneous; the
;;     binary then dies at its first parse with NoClassDefFoundError on
;;     CodeGeneratorRequest. Adding
;;     `--initialize-at-run-time=com.google.protobuf.JavaFeaturesProto` is refused for
;;     the same reason — protobuf initialises it at build time regardless.
;;   - clojure.lang.Reflector with a string class name hides the class from
;;     native-image's reachability analysis, which fails the same way.
;;
;; Reading the unknown field avoids the class entirely: no registry, no extension, no
;; initialisation, nothing new reachable. The three constants below are what that
;; costs. They are fixed by protobuf's own wire compatibility — an extension field
;; number and an enum value can never be renumbered without breaking every descriptor
;; ever serialised — and `nest-in-file-class-numbers-match-protobuf` in the test suite
;; pins them against the real generated classes, so a rename upstream fails loudly
;; here rather than silently emitting the wrong name again.

(def ^:private pb-java-extension-field
  "FeatureSet extension number of (pb.java), from java_features.proto."
  1001)

(def ^:private nest-in-file-class-field
  "Field number of nest_in_file_class within JavaFeatures."
  5)

(def ^:private nest-in-file-class-values
  "nest_in_file_class enum numbers."
  {2 :yes, 1 :no})

(defn- explicit-nest-in-file-class
  "`:yes`, `:no`, or nil when `features` does not set (pb.java).nest_in_file_class."
  [^DescriptorProtos$FeatureSet features]
  (some (fn [^ByteString bs]
          (some nest-in-file-class-values
                (-> (UnknownFieldSet/parseFrom bs)
                    (.getField nest-in-file-class-field)
                    (.getVarintList))))
        (-> (.getUnknownFields features)
            (.getField pb-java-extension-field)
            (.getLengthDelimitedList))))

(defn- nest-in-file-class?
  "Does the message live inside the file class? A setting on the message wins over
  one on the file; absent both, no — which is the edition 2024 default."
  [^DescriptorProtos$FileDescriptorProto fdp ^DescriptorProtos$DescriptorProto md]
  (= :yes
     (or (explicit-nest-in-file-class (.getFeatures (.getOptions md)))
         (explicit-nest-in-file-class (.getFeatures (.getOptions fdp))))))

(defn- file-class-name
  "The Java file class for `fdp`, edition 2024 and later only: java_outer_classname
  when set, else the camel-cased basename plus \"Proto\"."
  [^DescriptorProtos$FileDescriptorProto fdp]
  (let [opts (.getOptions fdp)]
    (if (.hasJavaOuterClassname opts)
      (.getJavaOuterClassname opts)
      (let [base (-> (.getName fdp)
                     (str/replace #"^.*/" "")
                     (str/replace #"\.proto$" ""))]
        (str (->> (str/split base #"[_-]")
                  (remove str/blank?)
                  (map str/capitalize)
                  (str/join))
             "Proto")))))

(defn- java-class-name
  "Fully-qualified Java class for the message at `path` — the proto names from the
  file root down, so [\"Outer\" \"Inner\"] is Outer's nested Inner. nil when the name
  cannot be derived, which means the generated code keeps today's DynamicMessage
  prototype. A non-nil answer is a hint the runtime verifies, not a guarantee.

  Nesting joins with `$` because that is how the JVM spells a nested class, and it
  applies to every level below the first: Java nests Inner inside Outer whatever
  the file options say, so only the OUTERMOST message's placement is in question."
  [^DescriptorProtos$FileDescriptorProto fdp ^DescriptorProtos$DescriptorProto md path]
  (let [opts (.getOptions fdp)
        pkg  (if (.hasJavaPackage opts) (.getJavaPackage opts) (.getPackage fdp))]
    (when (seq pkg)
      (cond
        ;; An explicit YES moves the message back inside the file class. Only
        ;; reachable from edition 2024+, which is why file-class-name needs no
        ;; pre-2024 rules.
        (nest-in-file-class? fdp md)
        (str pkg "." (file-class-name fdp) "$" (str/join "$" path))

        (top-level-java-class? fdp)
        (str pkg "." (str/join "$" path))))))

;; ---------------------------------------------------------------------------
;; the message tree
;;
;; A file's messages form a tree, and until now only its roots were emitted. The
;; two decisions that made the recursion more than a walk:
;;
;; WHAT THE RUNTIME IS ASKED FOR. protobuf-java offers no way to look a nested type
;; up from the file: FileDescriptor.findMessageTypeByName returns nil for
;; "Outer.Inner", for "Inner", for "Outer$Inner" and for the fully-qualified
;; "pkg.Outer.Inner" alike — its javadoc's "for nested types use Foo.Bar" does not
;; describe that method. Only Descriptor.findNestedTypeByName resolves one, walked a
;; segment at a time. So the emitted lookup is the dotted proto path and the runtime
;; splits it; that needs clj-protobuf >= 0.1.5.
;;
;; WHAT THE RECORD IS CALLED. Segments join with `-`. A proto identifier is
;; [A-Za-z_][A-Za-z0-9_]*, so `-` cannot occur in one, and Outer-Inner therefore
;; cannot collide with any message a schema is able to declare. `OuterInner` can:
;; it is a legal sibling name. The dash is not free, though — defrecord munges it
;; to `_` in the class name, so nested Outer.Inner and a top-level Outer_Inner both
;; compile to class Outer_Inner. That one is rejected outright rather than emitted;
;; see check-record-names!.
;;
;; map<k,v> fields synthesise a nested *Entry message with options.map_entry set.
;; protobuf's own gencode emits no class for those and neither does this — they are
;; an encoding detail, not a type the schema declared. The top-level-only walk
;; excluded them by accident; a recursive walk has to mean it, or every proto with a
;; map grows a bogus record.

(defn- record-name
  "Clojure record name for the message at `path`. See the note above on `-`."
  [path]
  (str/join "-" path))

(defn- check-record-names!
  "Refuse a file whose messages would generate the same record class.

  defrecord munges `-` to `_`, so nested Outer.Inner and a top-level Outer_Inner
  both land on class Outer_Inner and the second definition silently clobbers the
  first — a broken namespace that compiles. protoc refuses the analogous Java
  name collision; refusing it here costs a rename and saves a debugging session.
  -main turns this into a CodeGeneratorResponse error, so protoc reports it."
  [^DescriptorProtos$FileDescriptorProto fdp msgs]
  (doseq [[munged group] (group-by #(str/replace (:record-name %) "-" "_") msgs)
          :when (< 1 (count group))]
    (throw (ex-info (str "record name collision in " (.getName fdp) ": "
                         (str/join " and " (map :lookup-name group))
                         " would both generate the record class " munged
                         ". Rename one of them.")
                    {:file (.getName fdp) :class munged}))))

(defn- message-tree
  "Every message declared in `fdp`, each enclosing type before the types nested in
  it, as the maps the emitter renders. Map-entry types are skipped, at every level
  including the root.

  The root filter is for descriptors this plugin did not get from protoc. protoc
  synthesises map-entry types only as nested types and rejects the option written by
  hand — `option map_entry = true` fails with \"should not be set explicitly\" — but
  --descriptor_set_in accepts a set from anywhere, and a filter that holds only
  below the root would make this docstring a lie for one input class."
  [^DescriptorProtos$FileDescriptorProto fdp]
  (letfn [(map-entry? [^DescriptorProtos$DescriptorProto md]
            (.getMapEntry (.getOptions md)))
          (walk [^DescriptorProtos$DescriptorProto md path]
            (let [path (conj path (.getName md))]
              (cons {:record-name (record-name path)
                     :lookup-name (str/join "." path)
                     :java-class  (java-class-name fdp md path)
                     :fields      (mapv (fn [^DescriptorProtos$FieldDescriptorProto f]
                                          {:proto-name (.getName f)
                                           :key        (field-key-symbol (.getName f))})
                                        (.getFieldList md))}
                    (mapcat #(walk % path)
                            (remove map-entry? (.getNestedTypeList md))))))]
    (let [msgs (vec (mapcat #(walk % [])
                            (remove map-entry? (.getMessageTypeList fdp))))]
      (check-record-names! fdp msgs)
      msgs)))

;; ---------------------------------------------------------------------------
;; emission

(def default-runtime-namespaces
  "Namespaces the generated code requires.

  Only :service pulls grpc-java onto the classpath; :codec and :runtime are
  protobuf-only, which is why a message-only file never requires :service."
  {:codec   "clj-grpc.codec"
   :runtime "clj-grpc.runtime"
   :service "clj-grpc.runtime.service"})

(defn- runtime-namespaces
  "Resolve the emitted requires from plugin parameters, defaults for anything unset."
  [params]
  (merge default-runtime-namespaces
         (into {} (for [[k opt] [[:codec :codec_ns] [:runtime :runtime_ns] [:service :service_ns]]
                        :let [v (get params opt)]
                        :when (string? v)]
                    [k v]))))

(def ^:private b64-chunk-size
  "Maximum characters per emitted string literal.

  A class file's CONSTANT_Utf8 entry caps at 65535 bytes, and base64 is ASCII, so
  one character is one byte. A descriptor whose base64 form exceeds that cannot be
  AOT-compiled — verified: compiling a namespace holding a 70000-character literal
  fails in the constant pool, while the same content split across literals and
  joined with `str` compiles fine.

  Loading from source does not hit this, which is exactly why it would have gone
  unnoticed: the failure appears only when a consumer AOT-compiles generated code,
  and grows likelier with big schemas or keep_source_info=true."
  32768)

(defn- literal
  "`s` as Clojure source, split across several literals if it is large enough to
  overflow the constant pool. A short string emits as an ordinary literal, so the
  common case reads exactly as before."
  [s]
  (let [parts (mapv #(apply str %) (partition-all b64-chunk-size s))]
    (if (= 1 (count parts))
      (pr-str (first parts))
      (str "(str " (str/join "\n            " (map pr-str parts)) ")"))))

(defn- well-known-file?
  "Is `dep` a descriptor protobuf-java already carries?

  Only google/protobuf/* qualifies — that is what rt/known-file can look up.
  google/rpc and google/api live in separate artifacts and are, from here,
  ordinary imports."
  [dep]
  (str/starts-with? dep "google/protobuf/"))

(defn- dep-form
  "How a generated namespace obtains a dependency's FileDescriptor: from the
  sibling generated namespace, or — for well-known types — from protobuf-java's
  own descriptors.

  NB the decision is by path, NOT by whether the dep appears in
  file_to_generate. Those are different questions: protoc includes transitive
  imports in proto_file while listing only the requested files in
  file_to_generate, so keying off the latter would classify every custom
  transitive import as a well-known type and emit an rt/known-file call that
  cannot resolve. A custom import is instead assumed to have been generated by
  some invocation — the same assumption protoc-gen-go makes when it maps an
  import to a Go package."
  [dep generated? prefix]
  (if (and (well-known-file? dep) (not (generated? dep)))
    {:form (str "(rt/known-file " (pr-str dep) ")")}
    {:require (proto->ns dep prefix)
     :form    (str (proto->ns dep prefix) "/file-descriptor")}))

(defn emit-namespace
  "Render one .clj file for `fdp`.

  `rt-ns` maps :codec/:runtime/:service to the namespaces the output requires;
  see default-runtime-namespaces."
  ([^DescriptorProtos$FileDescriptorProto fdp generated? prefix keep-source-info?]
   (emit-namespace fdp generated? prefix keep-source-info? default-runtime-namespaces))
  ([^DescriptorProtos$FileDescriptorProto fdp generated? prefix keep-source-info? rt-ns]
  (let [ns-name (proto->ns (.getName fdp) prefix)
        deps    (mapv #(dep-form % generated? prefix) (.getDependencyList fdp))
        ;; protoc ships SourceCodeInfo — every comment and source span — in the
        ;; request. It is useless at runtime and dominates the embedded payload,
        ;; so drop it unless someone is generating docs.
        embed   (if keep-source-info?
                  fdp
                  (-> (.toBuilder fdp) (.clearSourceCodeInfo) (.build)))
        b64     (.encodeToString (Base64/getEncoder) (.toByteArray embed))
        msgs    (message-tree fdp)
        ;; The type hint is load-bearing, not tidiness. Unhinted, this compiles to
        ;; a reflective call that works fine on the JVM and fails in the native
        ;; image, where no reflection metadata is registered:
        ;;   No matching field found: getName for class ServiceDescriptorProto
        ;; Every interop call here must be hinted for the same reason.
        svcs    (mapv #(.getName ^DescriptorProtos$ServiceDescriptorProto %)
                      (.getServiceList fdp))
        sb      (StringBuilder.)
        line    #(doto sb (.append %) (.append "\n"))]
    (line (str ";; Generated by protoc-gen-clojure from " (.getName fdp) ". Do not edit."))
    (line ";;")
    (line ";; The FileDescriptorProto below is embedded verbatim; protobuf-java resolves")
    (line ";; its edition features at load time, so this file needs no regeneration when")
    (line ";; a new edition ships.")
    (line (str "(ns " ns-name))
    ;; The service runtime lives in the grpc module; requiring it from a
    ;; message-only file would drag grpc-java onto the classpath of a project
    ;; that never asked for RPC. So emit it only when there IS a service.
    (line (str "  (:require [" (:codec rt-ns) " :as codec]"
               "\n            [" (:runtime rt-ns) " :as rt]"
               (when (seq svcs) (str "\n            [" (:service rt-ns) " :as rts]"))
               (str/join "" (for [{:keys [require]} deps :when require]
                              (str "\n            [" require "]")))
               "))"))
    (line "")
    (line (str "(def ^:private descriptor-b64\n  " (literal b64) ")"))
    (line "")
    (line "(def file-descriptor")
    (line (str "  (rt/file-descriptor descriptor-b64\n                      ["
               (str/join "\n                       " (map :form deps))
               "]))"))
    (when (seq msgs)
      (line "")
      (line ";; ---------------------------------------------------------------")
      (line ";; messages")
      (line ";;")
      (line ";; The shape is known at codegen time, so the representation is too:")
      (line ";; a defrecord per type, its FieldDescriptors resolved once into")
      (line ";; vars, and straight-line ->proto/proto-> built on them. nil means")
      (line ";; absent, which is how a record (all keys always present) maps onto")
      (line ";; protobuf explicit presence.")
      (doseq [{msg-name :record-name lookup :lookup-name
               fields :fields java-class :java-class} msgs]
        (line "")
        (line (str "(defrecord " msg-name " [" (str/join " " (map :key fields)) "])"))
        (line (str "(def " msg-name "-prototype (rt/message file-descriptor "
                   ;; The dotted path, not the record name: this is a protobuf
                   ;; lookup, and the runtime walks it segment by segment because
                   ;; nothing on FileDescriptor resolves a nested type directly.
                   (pr-str lookup)
                   ;; Two independent runtime floors, and neither applies to every
                   ;; file. A dotted lookup above needs clj-protobuf 0.1.5, so only
                   ;; a file declaring nested messages does — top-level output is
                   ;; spelled exactly as it always was. This third argument needs
                   ;; 0.1.3, and is omitted entirely when the class name is
                   ;; unknown, so a file that gets no hint works against older
                   ;; runtimes still.
                   (when java-class (str " " (pr-str java-class)))
                   "))"))
        (doseq [{:keys [key proto-name]} fields]
          (line (str "(def ^:private " msg-name "--" key
                     " (rt/field " msg-name "-prototype " (pr-str proto-name) "))")))
        (line (str "(defn " msg-name "->proto"))
        (line (str "  \"Clojure -> protobuf. Takes the " msg-name " record or any map"))
        (line  "  with the same keys — records and plain maps are interchangeable.\"")
        (line (str "  ([m] (" msg-name "->proto m nil))"))
        (line  "  ([m opts]")
        (line (str "   (let [b (.newBuilderForType ^com.google.protobuf.Message "
                   msg-name "-prototype)]"))
        (doseq [{:keys [key]} fields]
          (line (str "     (codec/set-field! b " msg-name "--" key " (:" key " m) opts)")))
        (line  "     (.build b))))")
        (line (str "(defn proto->" msg-name))
        (line (str "  \"protobuf -> a " msg-name " record. Absent fields are nil.\""))
        (line (str "  ([msg] (proto->" msg-name " msg nil))"))
        (line  "  ([^com.google.protobuf.Message msg opts]")
        (line (str "   (->" msg-name))
        (doseq [{:keys [key]} fields]
          (line (str "    (codec/get-field msg " msg-name "--" key " opts)")))
        (line  "    )))")))


    (when (seq svcs)
      (line "")
      ;; Deliberately does not name the server/client namespaces: they are not
      ;; among the options this plugin takes, so under an override the guidance
      ;; would point at namespaces the project does not have.
      (line ";; services — pass the service value to your server, the methods to a client")
      (doseq [s svcs]
        (line (str "(def " s " (rts/service file-descriptor " (pr-str s) "))"))
        (line (str "(def " (str/lower-case s) "-methods (rts/methods-map " s "))"))))
    (str sb))))

;; ---------------------------------------------------------------------------
;; plugin protocol

(defn generate
  "CodeGeneratorRequest -> CodeGeneratorResponse."
  ^PluginProtos$CodeGeneratorResponse [^PluginProtos$CodeGeneratorRequest req]
  (let [params    (parse-params (.getParameter req))
        prefix    (when (string? (:ns_prefix params)) (:ns_prefix params))
        keep-src? (flag? (:keep_source_info params))
        rt-ns     (runtime-namespaces params)
        to-gen    (set (.getFileToGenerateList req))
        generated? #(contains? to-gen %)
        resp      (PluginProtos$CodeGeneratorResponse/newBuilder)]
    (doseq [^DescriptorProtos$FileDescriptorProto fdp (.getProtoFileList req)
            :when (to-gen (.getName fdp))]
      (let [ns-name (proto->ns (.getName fdp) prefix)]
        (.addFile resp (-> (PluginProtos$CodeGeneratorResponse$File/newBuilder)
                           (.setName (ns->path ns-name))
                           (.setContent (emit-namespace fdp generated? prefix keep-src? rt-ns))
                           (.build)))))
    (doto resp
      (.setSupportedFeatures
       (bit-or (.getNumber PluginProtos$CodeGeneratorResponse$Feature/FEATURE_PROTO3_OPTIONAL)
               (.getNumber PluginProtos$CodeGeneratorResponse$Feature/FEATURE_SUPPORTS_EDITIONS)))
      (.setMinimumEdition (.getNumber DescriptorProtos$Edition/EDITION_PROTO2))
      (.setMaximumEdition (.getNumber ^DescriptorProtos$Edition @max-supported-edition)))
    (.build resp)))

(def version
  "The released version. Single source of truth: the release workflow asserts
  this equals the tag rather than rewriting it, so a forgotten bump fails the
  release instead of shipping a binary that misreports itself."
  "0.2.0")

(defn -main
  "Read a CodeGeneratorRequest on stdin, write a CodeGeneratorResponse on stdout.

  Nothing else may touch stdout — a stray println corrupts the response and
  protoc reports an unintelligible parse error. `--version` is the one exception,
  and protoc never passes arguments, so the two cannot collide."
  [& args]
  (when (some #{"--version" "-v"} args)
    ;; System/out directly, NOT println: the native image is built with
    ;; --initialize-at-build-time, which snapshots clojure.core's *out* as it was
    ;; during the build, so println writes to a stale stream and silently prints
    ;; nothing while still exiting 0. The protocol path below is unaffected
    ;; because it also writes System/out rather than *out*.
    (let [^java.io.PrintStream out System/out]
      (.println out (str "protoc-gen-clojure " version
                         " (max edition "
                         (.name ^DescriptorProtos$Edition @max-supported-edition) ")")))
    (System/exit 0))
  (let [in  (java.io.BufferedInputStream. System/in)
        out (java.io.BufferedOutputStream. System/out)]
    (try
      (let [req  (PluginProtos$CodeGeneratorRequest/parseFrom in)
            resp (generate req)]
        (.writeTo resp out)
        (.flush out))
      (catch Throwable t
        ;; Report failures through the response, not by dying — protoc then
        ;; surfaces the message instead of "plugin failed with status code 1".
        (-> (PluginProtos$CodeGeneratorResponse/newBuilder)
            (.setError (str "protoc-gen-clojure: " (.getMessage t)))
            (.build)
            (.writeTo out))
        (.flush out)))
    (System/exit 0)))
