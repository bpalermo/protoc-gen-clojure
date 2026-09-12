(ns protoc-gen-clojure.plugin
  "protoc-gen-clojure — a protoc/buf codegen plugin that emits Clojure.

  Lets a Clojure project be an ordinary entry in buf.gen.yaml alongside
  protocolbuffers/go, protocolbuffers/java and the rest, instead of requiring
  hand-written Java interop to reach generated stubs.

  Emitted files require clj-protobuf.codec and clj-protobuf.runtime — the
  runtime this generates code FOR, a separate artifact
  (com.github.bpalermo/clj-protobuf on Clojars); files declaring a service also
  require clj-grpc.service. The generator is named after
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
    resolves, not what we emit. interop=true's typed read path needs resolved
    features at CODEGEN time too — presence and enum openness decide what it can
    spell — and gets them the same way, by handing the request to
    `FileDescriptor/buildFrom` (see `resolve-files`). Delegating, not
    reimplementing: there is still no defaults table here.

  Parameters (comma-separated, `protoc --clojure_out=key=value,key2=value2:DIR`):
    ns_prefix=foo          prefix every generated namespace with `foo.`
    keep_source_info=true  embed SourceCodeInfo (comments/spans) too; off by
                           default because it dominates the payload and is
                           useless at runtime
    interop=true           emit typed fast paths in BOTH directions: X->proto
                           when opts is nil, proto->X when opts is nil and the
                           message is the generated class. ~30-40% faster on
                           scalar-heavy writes, 2-3x on reads of small and
                           nested messages. A hard classpath contract: the
                           generated namespace then requires protoc's Java
                           classes at load. Off by default.
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
            DescriptorProtos$FieldDescriptorProto$Label
            DescriptorProtos$FieldDescriptorProto$Type
            DescriptorProtos$FeatureSet DescriptorProtos$ServiceDescriptorProto
            Descriptors$Descriptor Descriptors$EnumDescriptor
            Descriptors$EnumValueDescriptor Descriptors$FieldDescriptor
            Descriptors$FieldDescriptor$JavaType Descriptors$FieldDescriptor$Type
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
  clj-protobuf.codec's default :kebab naming so generated records and generic maps
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
;;   (edition 2024 or later only)           feature is set, on the message or the file
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

(defn- edition-2024+?
  "Is `fdp` an editions file at 2024 or later? Where nest_in_file_class exists, and
  where the outer-class default is the one file-class-name implements."
  [^DescriptorProtos$FileDescriptorProto fdp]
  (and (= "editions" (.getSyntax fdp))
       (>= (.getNumber (.getEdition fdp)) edition-2024-number)))

(defn- top-level-java-class?
  [^DescriptorProtos$FileDescriptorProto fdp]
  (or (.getJavaMultipleFiles (.getOptions fdp))
      (edition-2024+? fdp)))

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
        ;; An explicit YES moves the message back inside the file class — but only
        ;; from edition 2024, which is the gate file-class-name's single rule
        ;; depends on. The edition test is not redundant with the feature being
        ;; set: the feature is read off UNKNOWN FIELDS, which bypasses the
        ;; validation that would stop protoc accepting it in a proto2/proto3 file,
        ;; and --descriptor_set_in takes descriptor sets from anywhere. Without the
        ;; gate, such an input would be handed the 2024 "<Base>Proto" name — a
        ;; guess, from the rule set this deliberately does not implement for
        ;; earlier syntaxes. Falling through instead yields the pre-2024 answer:
        ;; the top-level name under java_multiple_files, and no hint otherwise.
        (and (edition-2024+? fdp) (nest-in-file-class? fdp md))
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

(def ^:private forbidden-accessor-suffixes
  "Accessor suffixes protoc refuses to generate bare, because they would collide
  with a method on java.lang.Object or on the Message interfaces. protoc appends
  `_` to each: a field named `class` is read with getClass_(), not getClass().
  From compiler/java/names.cc — IsForbidden compares exactly the string
  UnderscoresToCamelCase produces, which is what accessor-suffix computes.

  The read path is why this is here rather than left as a latent write-path bug:
  a setter we spell wrong does not compile, but `(.getClass m)` compiles fine and
  returns the object's Class. That is the one accessor mistake this plugin can
  make silently, so the one it must not make."
  #{"Class" "DefaultInstanceForType" "ParserForType" "SerializedSize"
    "AllFields" "DescriptorForType" "InitializationErrorString" "UnknownFields"
    "CachedSize"})

(defn- accessor-suffix
  "proto field name -> protoc's Java accessor suffix (UnderscoresToCamelCase):
  drop underscores, capitalise after an underscore or digit, preserve case
  elsewhere. repeat_count -> RepeatCount, f10 -> F10. A suffix that would shadow
  an inherited method gets protoc's trailing underscore; see above."
  ^String [^String s]
  (let [sb (StringBuilder. (.length s))]
    (loop [i 0 cap? true]
      (if (= i (.length s))
        (let [out (.toString sb)]
          (if (contains? forbidden-accessor-suffixes out) (str out "_") out))
        (let [c (.charAt s i)]
          (cond
            (= c \_) (recur (inc i) true)
            (Character/isDigit c) (do (.append sb c) (recur (inc i) true))
            :else (do (.append sb (if cap? (Character/toUpperCase c) c))
                      (recur (inc i) false))))))))

(def ^:private interop-scalar-coercion
  "For the interop fast path: FieldDescriptorProto$Type -> the Clojure coercion
  wrapping the typed setter's argument. Types absent here (enums, groups) take
  the codec path."
  {DescriptorProtos$FieldDescriptorProto$Type/TYPE_INT32    "int"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_SINT32   "int"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_SFIXED32 "int"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_UINT32   "int"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_FIXED32  "int"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_INT64    "long"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_SINT64   "long"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_SFIXED64 "long"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_UINT64   "long"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_FIXED64  "long"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_FLOAT    "float"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_DOUBLE   "double"
   DescriptorProtos$FieldDescriptorProto$Type/TYPE_BOOL     "boolean"})

(defn- interop-field-info
  "What the interop WRITE path needs to know about one field, or nil when the
  field takes the codec path there (repeated, map, enum, group, or a message
  type not declared in this file). local-messages maps a message's FULL proto
  name -> {:record :java-class}, and a type name in a FileDescriptorProto is
  always fully qualified, so a lookup miss is exactly the cross-file case.
  Map-entry types are absent from that index, so a map field could not match
  even if its LABEL_REPEATED did not already exclude it."
  [^DescriptorProtos$FieldDescriptorProto f local-messages]
  (let [type     (.getType f)
        repeated? (= (.getLabel f)
                     DescriptorProtos$FieldDescriptorProto$Label/LABEL_REPEATED)]
    (when-not repeated?
      (cond
        (contains? interop-scalar-coercion type)
        {:kind :scalar :coercion (interop-scalar-coercion type)}

        (= type DescriptorProtos$FieldDescriptorProto$Type/TYPE_STRING)
        {:kind :string}

        (= type DescriptorProtos$FieldDescriptorProto$Type/TYPE_BYTES)
        {:kind :bytes}

        (= type DescriptorProtos$FieldDescriptorProto$Type/TYPE_MESSAGE)
        (let [{:keys [record java-class]}
              (get local-messages (str/replace-first (.getTypeName f) #"^\." ""))]
          (when record
            {:kind :message :record record :java-class java-class}))))))

;; ---------------------------------------------------------------------------
;; the interop READ path
;;
;; The mirror of the write path above, and the half that was missing: with
;; interop=true every X->proto took typed setters while every proto->X still
;; called codec/get-field per field — a megamorphic invoker call, plus, for a
;; message-typed field, a map allocated only for the record constructor to throw
;; away. Measured over the bench fixtures the emitted read is 2-3x faster than
;; either arm on small and nested shapes, because it builds no intermediate.
;;
;; Three things decide what can be spelled here, and none of them is answerable
;; from a FileDescriptorProto alone:
;;
;;   PRESENCE. `(when (.hasX m) …)` where presence exists, a bare `(.getX m)`
;;   where it does not. Emitting the guard where protoc generated no hasser does
;;   not compile; omitting it where it did turns absence into a default,
;;   silently. proto2, proto3 `optional`, oneof members and editions EXPLICIT all
;;   answer differently, and the editions answer is a resolved feature.
;;
;;   ENUM OPENNESS. getXValue() is generated only for open enums; a closed one
;;   is read through its Java enum. Also a resolved feature under editions.
;;
;;   WHAT THE FIELD ACTUALLY IS. Delimited (group-like) fields are named after
;;   their message type rather than the field, so they stay on the codec.
;;
;; So this path works from RESOLVED descriptors — protobuf-java's own, built from
;; the request in `resolve-files`. That is the same delegation the emitted file
;; makes when it hands its embedded descriptor to FileDescriptor/buildFrom at
;; load: protobuf-java resolves features, this plugin still owns no defaults
;; table. Where a file cannot be resolved there is simply no typed read arm.

(def ^:private read-plain-java-types
  "JavaTypes a generated getter already returns in the codec's representation, so
  the read is the getter and nothing else. BYTE_STRING, ENUM and MESSAGE are the
  three that need converting; see read-info."
  #{Descriptors$FieldDescriptor$JavaType/INT
    Descriptors$FieldDescriptor$JavaType/LONG
    Descriptors$FieldDescriptor$JavaType/FLOAT
    Descriptors$FieldDescriptor$JavaType/DOUBLE
    Descriptors$FieldDescriptor$JavaType/BOOLEAN
    Descriptors$FieldDescriptor$JavaType/STRING})

(defn- local-record
  "The record name for a message-typed field this file can convert itself, or
  nil. nil covers a cross-file type, a group-like (DELIMITED) field — whose Java
  accessor is named after the message type, not the field — and a message whose
  Java class name could not be derived, which is what the sibling read needs to
  hint its argument."
  [^Descriptors$FieldDescriptor fd local-messages]
  (when (and (= Descriptors$FieldDescriptor$JavaType/MESSAGE (.getJavaType fd))
             (not= Descriptors$FieldDescriptor$Type/GROUP (.getType fd)))
    (let [{:keys [record java-class]}
          (get local-messages (.getFullName (.getMessageType fd)))]
      (when java-class record))))

(defn- element-conv
  "How one element of a repeated or map-valued field converts: a function from
  the expression producing the element to its converted form, `identity` when it
  needs none — which is what lets a repeated scalar go through `vec` — or nil
  when the element kind has no fast spelling and the whole field stays on the
  codec."
  [^Descriptors$FieldDescriptor fd local-messages]
  (let [jt (.getJavaType fd)]
    (cond
      (contains? read-plain-java-types jt) identity

      (= Descriptors$FieldDescriptor$JavaType/BYTE_STRING jt)
      #(str "(.toByteArray ^com.google.protobuf.ByteString " % ")")

      ;; Enums are the exception, and deliberately: a singular enum can fall back
      ;; to codec/get-field for a number no declared value names, because the
      ;; fallback reads the whole field. Inside a collection there is no
      ;; per-element equivalent short of reproducing protobuf's synthetic
      ;; UNKNOWN_ENUM_VALUE_* naming, so repeated and map-valued enums stay on
      ;; the codec entirely.
      :else
      (when-let [r (local-record fd local-messages)]
        #(str "(proto->" r "--map " % ")")))))

(defn- enum-clauses
  "`case` clauses mapping an enum's declared numbers to the keywords the codec
  reads them back as, or nil when the enum cannot be spelled as a `case`: no
  values at all, or two values sharing a number (allow_alias), where the codec's
  own two arms disagree on which name wins and this path declines to pick."
  [^Descriptors$EnumDescriptor ed]
  (let [vs   (.getValues ed)
        nums (map #(.getNumber ^Descriptors$EnumValueDescriptor %) vs)]
    (when (and (seq vs) (= (count nums) (count (set nums))))
      (str/join " " (map (fn [^Descriptors$EnumValueDescriptor v]
                           (str (.getNumber v) " :" (.getName v)))
                         vs)))))

(defn- read-info
  "How the typed read arm spells one field —

    {:expr    the expression, with `m` bound to the typed message
     :always?  true when it can never be nil, so a nested map always carries the key
     :sibling  the record whose map-building read this expression calls, if any}

  — or nil when the field stays on the codec there. `var-name` names the field's
  FieldDescriptor var, used for the codec fallback inside an enum's `case`."
  [^Descriptors$FieldDescriptor fd local-messages ^String var-name]
  (let [acc (accessor-suffix (.getName fd))
        jt  (.getJavaType fd)]
    (cond
      ;; A map reads back as a Clojure map, nil when empty. getXMap() hands over
      ;; the entries directly: no entry messages are materialized on either side.
      ;;
      ;; The reduce, and not `(into {} jm)`, which reads better and costs more:
      ;; into conj!s a java.util.Map$Entry at a time, and measured over a
      ;; LinkedHashMap it runs ~1.7x slower and allocates ~2.4x more at every
      ;; size from 4 entries to 256 (680 B against 232 at 4, 27.8 KB against 11.6
      ;; at 256). This is the same shape the codec uses on its own fast arm.
      (.isMapField fd)
      (let [vfd  (.findFieldByName (.getMessageType fd) "value")
            conv (element-conv vfd local-messages)]
        (when conv
          {:always? false
           :sibling (local-record vfd local-messages)
           :expr (str "(let [jm (.get" acc "Map m)] (when-not (.isEmpty jm) "
                      "(persistent! (reduce (fn [acc ^java.util.Map$Entry e]"
                      " (assoc! acc (.getKey e) " (conv "(.getValue e)") "))"
                      " (transient {}) (.entrySet jm)))"
                      "))")}))

      ;; Repeated: a vector, nil when empty — the codec's own reading of an empty
      ;; repeated field, which has no presence to distinguish it from absence.
      (.isRepeated fd)
      (let [conv (element-conv fd local-messages)]
        (when conv
          {:always? false
           :sibling (local-record fd local-messages)
           :expr (str "(let [l (.get" acc "List m)] (when-not (.isEmpty l) "
                      (if (identical? identity conv)
                        "(vec l)"
                        (str "(persistent! (reduce (fn [acc v] (conj! acc "
                             (conv "v") ")) (transient []) l))"))
                      "))")}))

      :else
      (let [record (local-record fd local-messages)
            core
            (cond
              (contains? read-plain-java-types jt) (str "(.get" acc " m)")

              (= Descriptors$FieldDescriptor$JavaType/BYTE_STRING jt)
              (str "(.toByteArray (.get" acc " m))")

              (= Descriptors$FieldDescriptor$JavaType/ENUM jt)
              ;; Hinted because getEnumType() has a bridge overload returning
              ;; Internal$EnumLiteMap, and an unhinted binding leaves the calls
              ;; below to reflection.
              (let [^Descriptors$EnumDescriptor ed (.getEnumType fd)]
                (when-let [clauses (enum-clauses ed)]
                  (str "(case "
                       (if (.isClosed ed)
                         ;; No getXValue() for a closed enum — it is generated
                         ;; only where an unknown number is representable. The
                         ;; Java enum's own getNumber() needs no hint: `m` is
                         ;; typed, so the getter's return type is known.
                         (str "(.getNumber (.get" acc " m))")
                         (str "(.get" acc "Value m)"))
                       " " clauses
                       ;; An open enum can hold a number no value declares. The
                       ;; codec names those the way protobuf does; reproducing
                       ;; that here would mean owning protobuf's synthetic
                       ;; naming, so the default arm asks the codec instead.
                       " (codec/get-field m " var-name " nil))")))

              record (str "(proto->" record "--map (.get" acc " m))"))]
        (when core
          {:always? (not (.hasPresence fd))
           :sibling record
           :expr (if (.hasPresence fd)
                   (str "(when (.has" acc " m) " core ")")
                   core)})))))

;; ---------------------------------------------------------------------------
;; the compiled-arm READ path
;;
;; The same idea as the interop read above, for the arm that has no generated
;; Java classes: a consumer who runs protoc but not javac. There the prototype is
;; clj-protobuf's own compiled message, whose fields live in a slot array, and
;; from 0.3.0 the runtime exposes three symbols that let generated code read it
;; directly — rt/compiled-message?, rt/slot, rt/slot-of — with the slot's
;; contents a documented contract per kind.
;;
;; This path is emitted by DEFAULT, which the interop one is not, so two of its
;; properties are load-bearing:
;;
;;   IT SETS THE RUNTIME FLOOR. Every file emitting a slot read requires
;;   clj-protobuf 0.3.0. The earlier floors (0.1.3 for the Java-class hint, 0.1.5
;;   for a dotted nested lookup) bind only the files that use them; this one binds
;;   every regenerated file. That is a deliberate decision, not an accident.
;;
;;   THE INDEX IS BAKED. rt/slot takes the field's DECLARATION INDEX, which
;;   clj-protobuf guarantees as public contract from 0.3.0 — never a field number,
;;   never an internal ordering — so the emitter writes the literal rather than
;;   calling rt/slot-of at load. check-slot-indices! below is what keeps that
;;   honest from this side.
;;
;; Two constraints come from the runtime and neither is cosmetic. rt/slot is
;; called as a plain fn on Object and NOTHING here hints a class clj-protobuf
;; defines: generated code that did would break on that library's plain-clj leg,
;; where its namespaces reload in one JVM. And every slot read is guarded by
;; rt/compiled-message?, because rt/slot deliberately does not check — a message
;; from another arm throws rather than answering.
;;
;; It reaches shapes the interop path cannot. A group-like field is an ordinary
;; message slot here, because there are no Java accessors to name it after, and a
;; message whose Java class name could not be derived converts fine, because the
;; sibling takes the nested compiled message rather than a hinted class.

(defn- local-slot-record
  "The record name for a message-typed field this file can convert itself, or
  nil for a cross-file type. No Java class is required, unlike the interop path."
  [^Descriptors$FieldDescriptor fd local-messages]
  (when (= Descriptors$FieldDescriptor$JavaType/MESSAGE (.getJavaType fd))
    (:record (get local-messages (.getFullName (.getMessageType fd))))))

(defn- slot-conv
  "How one slot value converts to the Clojure value the codec would return: a
  function from the expression producing it, `identity` when the slot already
  holds it — which is most fields, and the reason this path is fast — or nil
  when the kind has no fast spelling."
  [^Descriptors$FieldDescriptor fd local-messages]
  (let [jt (.getJavaType fd)]
    (cond
      (contains? read-plain-java-types jt) identity

      (= Descriptors$FieldDescriptor$JavaType/BYTE_STRING jt)
      #(str "(.toByteArray ^com.google.protobuf.ByteString " % ")")

      ;; Enums inside a collection stay on the codec, for the reason the interop
      ;; path gives: an undeclared number has no per-element fallback short of
      ;; reproducing protobuf's synthetic naming.
      :else
      (when-let [r (local-slot-record fd local-messages)]
        #(str "(proto->" r "--slot-map " % ")")))))

(defn- implicit-default-literal
  "What a field with IMPLICIT presence reads back as when its slot is nil, as
  source. A nil slot means `the default` there rather than `absent`, and the
  codec substitutes the descriptor's default and converts it; this bakes the
  converted form. Implicit presence forbids an explicit default, so every one of
  these is the type's zero — but the TYPE matters: the codec hands back the
  Integer and Float protobuf-java declares, so `0` and `0.0`, which are Long and
  Double in Clojure, would be equal but not identical in kind."
  [^Descriptors$FieldDescriptor fd]
  (condp = (.getJavaType fd)
    Descriptors$FieldDescriptor$JavaType/INT     "(int 0)"
    Descriptors$FieldDescriptor$JavaType/LONG    "0"
    Descriptors$FieldDescriptor$JavaType/FLOAT   "(float 0.0)"
    Descriptors$FieldDescriptor$JavaType/DOUBLE  "0.0"
    Descriptors$FieldDescriptor$JavaType/BOOLEAN "false"
    Descriptors$FieldDescriptor$JavaType/STRING  "\"\""
    Descriptors$FieldDescriptor$JavaType/BYTE_STRING "(byte-array 0)"
    Descriptors$FieldDescriptor$JavaType/ENUM
    (str ":" (.getName ^Descriptors$EnumValueDescriptor (.getDefaultValue fd)))
    nil))

(defn- slot-read-info
  "How the compiled arm's read spells one field — the same {:expr :always?
  :sibling} the interop read returns — or nil when the field stays on the codec.
  `msg` is the message in the emitted code, on both the record fn and the
  map-building sibling, so the expression needs no source parameter."
  [^Descriptors$FieldDescriptor fd local-messages ^String var-name ^long idx]
  (let [jt   (.getJavaType fd)
        slot (str "(rt/slot msg " idx ")")]
    (cond
      (.isMapField fd)
      (let [vfd  (.findFieldByName (.getMessageType fd) "value")
            conv (slot-conv vfd local-messages)]
        (when conv
          {:always? false
           :sibling (local-slot-record vfd local-messages)
           ;; java.util.Map is a JDK type, so hinting the local is allowed where
           ;; hinting clj-protobuf's own is not. A nil slot and an empty map both
           ;; mean the same nil, which is what the codec reads an empty map as.
           :expr (str "(let [^java.util.Map jm " slot "] (when (and jm (pos? (.size jm))) "
                      "(persistent! (reduce (fn [acc ^java.util.Map$Entry e]"
                      " (assoc! acc (.getKey e) " (conv "(.getValue e)") "))"
                      " (transient {}) (.entrySet jm)))"
                      "))")}))

      (.isRepeated fd)
      (let [conv (slot-conv fd local-messages)]
        (when conv
          {:always? false
           :sibling (local-slot-record fd local-messages)
           :expr (str "(let [^java.util.List l " slot "] (when (and l (pos? (.size l))) "
                      (if (identical? identity conv)
                        "(vec l)"
                        (str "(persistent! (reduce (fn [acc v] (conj! acc "
                             (conv "v") ")) (transient []) l))"))
                      "))")}))

      :else
      (let [record (local-slot-record fd local-messages)
            conv   (cond
                     (contains? read-plain-java-types jt) identity

                     (= Descriptors$FieldDescriptor$JavaType/BYTE_STRING jt)
                     #(str "(.toByteArray ^com.google.protobuf.ByteString " % ")")

                     (= Descriptors$FieldDescriptor$JavaType/ENUM jt)
                     (let [^Descriptors$EnumDescriptor ed (.getEnumType fd)]
                       (when-let [clauses (enum-clauses ed)]
                         ;; The slot holds the number, so there is no getXValue
                         ;; question here and open and closed enums read alike.
                         #(str "(case " % " " clauses
                               " (codec/get-field msg " var-name " nil))")))

                     record #(str "(proto->" record "--slot-map " % ")"))]
        (when conv
          (let [presence? (.hasPresence fd)
                identity? (identical? identity conv)]
            {:always? (not presence?)
             :sibling record
             :expr
             (cond
               ;; nil passes straight through as absent
               (and presence? identity?) slot
               presence? (str "(when-some [v " slot "] " (conv "v") ")")
               :else
               (str "(let [v " slot "] (if (nil? v) "
                    (implicit-default-literal fd) " "
                    (if identity? "v" (conv "v")) "))"))}))))))

(defn- check-slot-indices!
  "Refuse to bake a slot index the descriptor does not agree with.

  The emitter walks `(.getFieldList md)` and uses a field's POSITION there as its
  slot; clj-protobuf's contract is the field's declaration index, which
  protobuf-java reports as `(.getIndex fd)` on the separately built descriptor.
  Those are two sources for one number, so comparing them is a real check rather
  than a value compared against itself — which is exactly why clj-protobuf
  declined the mirror-image assertion at handle construction, where it would
  pass forever including in the failure it exists to catch."
  [^DescriptorProtos$FileDescriptorProto fdp ^String lookup ^Descriptors$Descriptor rd]
  (doseq [[i ^Descriptors$FieldDescriptor fd] (map-indexed vector (.getFields rd))]
    (when-not (= i (.getIndex fd))
      (throw (ex-info (str "slot index disagreement in " (.getName fdp) ": field "
                           (.getName fd) " of " lookup " is at position " i
                           " but reports declaration index " (.getIndex fd)
                           ". The compiled arm's slot would read another field.")
                      {:file (.getName fdp) :message lookup :field (.getName fd)})))))

(defn- message-tree
  "Every message declared in `fdp`, each enclosing type before the types nested in
  it, as the maps the emitter renders. Map-entry types are skipped, at every level
  including the root.

  `resolved` is `fdp` as a built FileDescriptor, or nil. It carries what only
  feature resolution can answer — presence, enum openness, what a delimited field
  really is — so the typed READ path is emitted for a message only when it is
  present; the write path and everything else never needed it. The walk descends
  both trees together rather than looking types up by name, because
  FileDescriptor offers no lookup for a nested type.

  The root map-entry filter is for descriptors this plugin did not get from
  protoc. protoc synthesises map-entry types only as nested types and rejects the
  option written by hand — `option map_entry = true` fails with \"should not be
  set explicitly\" — but --descriptor_set_in accepts a set from anywhere, and a
  filter that holds only below the root would make this docstring a lie for one
  input class."
  ([^DescriptorProtos$FileDescriptorProto fdp] (message-tree fdp nil))
  ([^DescriptorProtos$FileDescriptorProto fdp ^Descriptors$FileDescriptor resolved]
  (letfn [(map-entry? [^DescriptorProtos$DescriptorProto md]
            (.getMapEntry (.getOptions md)))
          (walk [^DescriptorProtos$DescriptorProto md path locals
                 ^Descriptors$Descriptor rd]
            (let [path  (conj path (.getName md))
                  mname (record-name path)
                  ;; Every message, nested ones included, before a single slot
                  ;; index is baked from this descriptor.
                  _     (when rd (check-slot-indices! fdp (str/join "." path) rd))]
              (cons {:record-name mname
                     :lookup-name (str/join "." path)
                     :java-class  (java-class-name fdp md path)
                     :fields      (mapv (fn [i ^DescriptorProtos$FieldDescriptorProto f]
                                          (let [k   (field-key-symbol (.getName f))
                                                ;; Spelled out rather than threaded: every
                                                ;; interop call in this file has to resolve at
                                                ;; compile time or the native image dies on it.
                                                rfd (when rd (.findFieldByName rd (.getName f)))]
                                            {:proto-name (.getName f)
                                             :key        k
                                             :setter     (str "set" (accessor-suffix (.getName f)))
                                             :interop    (interop-field-info f locals)
                                             :read       (when rfd
                                                           (read-info rfd locals (str mname "--" k)))
                                             :slot-read  (when rfd
                                                           (slot-read-info rfd locals (str mname "--" k) i))}))
                                        (range) (.getFieldList md))}
                    (mapcat (fn [^DescriptorProtos$DescriptorProto n]
                              (walk n path locals
                                    (when rd (.findNestedTypeByName rd (.getName n)))))
                            (remove map-entry? (.getNestedTypeList md))))))]
    (let [;; Every same-file message by its FULL proto name, with both spellings
          ;; a field needs: the record whose ->proto converts a map, and the
          ;; Java class that ->proto returns. The class is what lets the
          ;; interop arm hint the call — without it, protoc's builders overload
          ;; setX for the message and its Builder, Clojure cannot pick one at
          ;; compile time, and every message-typed field reflects at run time.
          pkg         (.getPackage fdp)
          index       (fn index [^DescriptorProtos$DescriptorProto md path]
                        (let [path (conj path (.getName md))]
                          (concat (when-not (map-entry? md)
                                    [[(str/join "." (if (seq pkg) (cons pkg path) path))
                                      {:record     (record-name path)
                                       :java-class (java-class-name fdp md path)}]])
                                  (mapcat #(index % path) (.getNestedTypeList md)))))
          locals      (into {} (mapcat #(index % []) (.getMessageTypeList fdp)))
          msgs (vec (mapcat (fn [^DescriptorProtos$DescriptorProto md]
                              (walk md [] locals
                                    (when resolved
                                      (.findMessageTypeByName resolved (.getName md)))))
                            (remove map-entry? (.getMessageTypeList fdp))))]
      (check-record-names! fdp msgs)
      msgs))))

;; ---------------------------------------------------------------------------
;; emission

(def default-runtime-namespaces
  "Namespaces the generated code requires.

  Only :service pulls grpc-java onto the classpath; :codec and :runtime are
  protobuf-only, which is why a message-only file never requires :service.

  These defaults changed in 0.4.0, from clj-grpc.codec / clj-grpc.runtime /
  clj-grpc.runtime.service: the artifact is clj-protobuf, and namespaces that
  say so beat namespaces that name a different artifact. Code generated by
  earlier releases keeps working — clj-protobuf ships the old namespaces as
  deprecated aliases — and these options can pin the old names if regeneration
  is not an option."
  {:codec   "clj-protobuf.codec"
   :runtime "clj-protobuf.runtime"
   :service "clj-grpc.service"})

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

(defn- try-resolve
  "`fdp` as a built FileDescriptor, or nil when it cannot be built here — a file
  with dependencies, which only the request can supply, or one protoc never
  validated. Callers with the request use `resolve-files` instead; this is what
  makes a self-contained descriptor still get the typed read path."
  ^Descriptors$FileDescriptor [^DescriptorProtos$FileDescriptorProto fdp]
  (try
    (Descriptors$FileDescriptor/buildFrom fdp (into-array Descriptors$FileDescriptor []))
    (catch Throwable _ nil)))

(defn emit-namespace
  "Render one .clj file for `fdp`.

  `rt-ns` maps :codec/:runtime/:service to the namespaces the output requires;
  see default-runtime-namespaces. `resolved` is `fdp` built into a
  FileDescriptor; it is only consulted for interop=true's typed read path, and
  the arity without it resolves what it can on its own."
  ([^DescriptorProtos$FileDescriptorProto fdp generated? prefix keep-source-info?]
   (emit-namespace fdp generated? prefix keep-source-info? default-runtime-namespaces false))
  ([^DescriptorProtos$FileDescriptorProto fdp generated? prefix keep-source-info? rt-ns]
   (emit-namespace fdp generated? prefix keep-source-info? rt-ns false))
  ([^DescriptorProtos$FileDescriptorProto fdp generated? prefix keep-source-info? rt-ns interop?]
   (emit-namespace fdp generated? prefix keep-source-info? rt-ns interop?
                   (try-resolve fdp)))
  ([^DescriptorProtos$FileDescriptorProto fdp generated? prefix keep-source-info? rt-ns interop?
    ^Descriptors$FileDescriptor resolved]
  (let [ns-name (proto->ns (.getName fdp) prefix)
        deps    (mapv #(dep-form % generated? prefix) (.getDependencyList fdp))
        ;; protoc ships SourceCodeInfo — every comment and source span — in the
        ;; request. It is useless at runtime and dominates the embedded payload,
        ;; so drop it unless someone is generating docs.
        embed   (if keep-source-info?
                  fdp
                  (-> (.toBuilder fdp) (.clearSourceCodeInfo) (.build)))
        b64     (.encodeToString (Base64/getEncoder) (.toByteArray embed))
        ;; Resolved for EVERY file now, not only interop ones: the compiled
        ;; arm's typed read is emitted by default and needs the same answers.
        msgs    (message-tree fdp resolved)
        ;; Which messages need the private map-building read: exactly those some
        ;; typed read calls for a nested field. Emitting one per message would
        ;; double the file for nothing.
        ;; Two sets, not one: a field the interop arm cannot spell may still be
        ;; readable from a slot — a group-like field is the standard case — so
        ;; the two arms do not nest the same messages. Keyed on being REFERENCED
        ;; and nothing else: a message whose own fields all fall back to the
        ;; codec, or which has no fields at all, is still a legitimate nested
        ;; type, and skipping its sibling would leave the caller calling a
        ;; declared-but-undefined fn.
        ;; Empty unless interop is on: this sibling HINTS the generated Java
        ;; class in its parameter, and a hint is resolved when the namespace
        ;; loads — emitting one in a default file would put protoc's Java on
        ;; every consumer's classpath, which is the whole thing interop=true
        ;; exists to opt into.
        siblings-java (if interop?
                        (into #{} (comp (mapcat :fields) (keep (comp :sibling :read))) msgs)
                        #{})
        siblings-slot (into #{} (comp (mapcat :fields) (keep (comp :sibling :slot-read))) msgs)
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
      ;; Typed arms call sibling fns for message-typed fields — ->proto on the
      ;; way in, a private map-building read on the way out — and declaration
      ;; order is proto order, so forward references need declaring. Only the
      ;; codec arm references no siblings, which is why this did not exist
      ;; before either arm did.
      (let [decls (concat (when interop?
                            (map #(str (:record-name %) "->proto") msgs))
                          (map #(str "proto->" % "--map")
                               (filter siblings-java (map :record-name msgs)))
                          (map #(str "proto->" % "--slot-map")
                               (filter siblings-slot (map :record-name msgs))))]
        (when (seq decls)
          (line (str "(declare " (str/join " " decls) ")"))))
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
                   ;; file. A dotted lookup above needs clj-protobuf 0.1.5
                   ;; (com.github.bpalermo/clj-protobuf on Clojars — its first
                   ;; published version, so in practice any release), so only
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
        (if (and interop? java-class)
          ;; The interop arm: typed setters against the generated class, taken
          ;; only when opts is nil — the codec arm below keeps every opts
          ;; semantic bit-for-bit. Fields the fast path cannot spell (enum,
          ;; repeated, map, cross-file message) use the codec inline; a
          ;; non-map message value falls back to the codec's coercions too.
          ;; This arm makes the generated namespace REQUIRE the protoc Java
          ;; classes at load — that is interop=true's documented contract.
          (do
            (line  "   (if (nil? opts)")
            (line (str "     (let [b (" java-class "/newBuilder)]"))
            (doseq [{:keys [key setter interop]} fields]
              (case (:kind interop)
                :scalar
                (line (str "       (when-some [v (:" key " m)] (." setter " b ("
                           (:coercion interop) " v)))"))
                :string
                (line (str "       (when-some [v (:" key " m)] (." setter " b ^String v))"))
                :bytes
                (line (str "       (when-some [v (:" key " m)] (." setter " b "
                           "(if (bytes? v) (com.google.protobuf.ByteString/copyFrom ^bytes v) "
                           "^com.google.protobuf.ByteString v)))"))
                ;; The sibling ->proto call is hinted with the field's own
                ;; Java class. Without the hint the call is reflective: protoc
                ;; overloads setX for the message AND its Builder, so Clojure
                ;; cannot resolve it at compile time — measured at ~7 µs and
                ;; 12 KB per nested level, on the arm whose whole point is
                ;; direct typed calls. The hint is the class ->proto returns,
                ;; which is the class this field is declared with.
                :message
                (line (str "       (when-some [v (:" key " m)] (if (map? v) (." setter " b "
                           (when-let [jc (:java-class interop)] (str "^" jc " "))
                           "(" (:record interop) "->proto v nil)) (codec/set-field! b "
                           msg-name "--" key " v nil)))"))
                ;; nil: codec path inside the fast arm, opts nil.
                (line (str "       (codec/set-field! b " msg-name "--" key
                           " (:" key " m) nil)"))))
            (line  "       (.build b))")
            (line (str "     (let [b (.newBuilderForType ^com.google.protobuf.Message "
                       msg-name "-prototype)]"))
            (doseq [{:keys [key]} fields]
              (line (str "       (codec/set-field! b " msg-name "--" key " (:" key " m) opts)")))
            (line  "       (.build b)))))"))
          (do
            (line (str "   (let [b (.newBuilderForType ^com.google.protobuf.Message "
                       msg-name "-prototype)]"))
            (doseq [{:keys [key]} fields]
              (line (str "     (codec/set-field! b " msg-name "--" key " (:" key " m) opts)")))
            (line  "     (.build b))))")))
        (let [typed-read? (boolean (and interop? java-class (some :read fields)))
              slot-read?  (boolean (some :slot-read fields))
              ;; The codec read of one field, for a field the fast arm cannot
              ;; spell. `from` is the message local: `m` inside the typed arm.
              codec-read  (fn [from key opts] (str "(codec/get-field " from " " msg-name "--" key
                                                   " " opts ")"))]
          (line (str "(defn proto->" msg-name))
          (line (str "  \"protobuf -> a " msg-name " record. Absent fields are nil.\""))
          (line (str "  ([msg] (proto->" msg-name " msg nil))"))
          (line  "  ([^com.google.protobuf.Message msg opts]")
          ;; Up to three arms, in the order a message is cheapest to read:
          ;; the generated Java class, then the compiled codec's slots, then the
          ;; codec itself — which is also where any non-nil opts goes, because
          ;; every opts semantic belongs to the codec and only to it.
          (let [ctor (fn [indent exprs closing]
                       (line (str indent "(->" msg-name))
                       (doseq [e exprs] (line (str indent " " e)))
                       (line (str indent " " closing)))
                codec-arm (map #(codec-read "msg" (:key %) "opts") fields)
                slot-arm  (map #(or (:expr (:slot-read %))
                                    (codec-read "msg" (:key %) "nil")) fields)]
            (cond
              (and typed-read? slot-read?)
              (do
                ;; `instance?` and not just `(nil? opts)`: this arm reads one
                ;; concrete class's own accessors, and proto->X accepts any
                ;; Message. It compiles to an instanceof.
                (line (str "   (cond"))
                (line (str "     (and (nil? opts) (instance? " java-class " msg))"))
                (line (str "     (let [^" java-class " m msg]"))
                (ctor "       " (map #(or (:expr (:read %))
                                          (codec-read "m" (:key %) "nil")) fields) "))")
                (line  "")
                (line  "     (and (nil? opts) (rt/compiled-message? msg))")
                (ctor "     " slot-arm ")")
                (line  "")
                (line  "     :else")
                (ctor "     " codec-arm "))))"))

              typed-read?
              (do
                (line (str "   (if (and (nil? opts) (instance? " java-class " msg))"))
                (line (str "     (let [^" java-class " m msg]"))
                (ctor "       " (map #(or (:expr (:read %))
                                          (codec-read "m" (:key %) "nil")) fields) "))")
                (ctor "     " codec-arm "))))"))

              slot-read?
              (do
                (line  "   (if (and (nil? opts) (rt/compiled-message? msg))")
                (ctor "     " slot-arm ")")
                (ctor "     " codec-arm "))))"))

              :else
              (ctor "   " codec-arm ")))")))
          ;; The map-building read, private, emitted only for a message some
          ;; other message nests. A nested message reads back as a plain map on
          ;; the codec path — the runtime cannot know the record classes — and a
          ;; Clojure record is not = to a map with the same keys, so returning
          ;; records here would break every consumer comparing a decoded value
          ;; and would put the two arms in disagreement. Records are faster
          ;; (measurably: 3556 ns against 5033 on a list of small messages) and
          ;; are a separate, opt-in question.
          ;; Keyed on being referenced, NOT on this message having a typed read
          ;; of its own: a message every one of whose fields falls back to the
          ;; codec — or one with no fields at all — is still a legitimate nested
          ;; type, and skipping it here would leave the caller with a call to a
          ;; declared-but-undefined fn.
          (letfn [(sibling-fn
                    [fn-name info-key params src docline]
                    ;; One map-building sibling, over whichever read info the arm
                    ;; uses. The two differ only in where a field's value comes
                    ;; from and what the parameter is called; the shape that
                    ;; decides the allocation is the same.
                    (let [info   (fn [f] (get f info-key))
                          always (filterv (comp :always? info) fields)
                          conds  (filterv (complement (comp :always? info)) fields)
                          pair   (fn [f]
                                   (str ":" (:key f) " "
                                        (if (:always? (info f)) (:expr (info f))
                                            (str (:key f) "--v"))))
                          lit    (fn [fs indent]
                                   (str "{" (str/join (str "\n" (apply str (repeat (inc indent) " ")))
                                                      (map pair fs))
                                        "}"))]
                      (line (str "(defn- " fn-name))
                      (line (str "  \"" msg-name " " docline))
                      (line  "  the same values, minus the keys the codec's read leaves out.\"")
                      (line (str "  " params))
                      (cond
                        (empty? conds)
                        (line (str "  " (lit always 2) ")"))

                        :else
                        (do
                          (line (str "  (let [" (str/join "\n        "
                                                          (map (fn [f]
                                                                 (str (:key f) "--v "
                                                                      (or (:expr (info f))
                                                                          (codec-read src (:key f) "nil"))))
                                                               conds))
                                     "]"))
                          (line (str "    (if "
                                     (let [tests (map #(str "(some? " (:key %) "--v)") conds)]
                                       (if (= 1 (count conds))
                                         (first tests)
                                         (str "(and " (str/join " " tests) ")")))))
                          (line (str "      " (lit fields 6)))
                          (if (= 1 (count conds))
                            (line (str "      " (lit always 6) ")))"))
                            (do
                              (line  "      (persistent!")
                              (line (str "       (cond-> (transient " (lit always 24) ")"))
                              (doseq [f conds]
                                (line (str "         (some? " (:key f) "--v) (assoc! :" (:key f)
                                           " " (:key f) "--v)")))
                              (line  "         )))))")))))))]
            (when (and (contains? siblings-java msg-name) java-class)
              (sibling-fn (str "proto->" msg-name "--map") :read
                          (str "[^" java-class " m]") "m"
                          "as the plain map a nested field reads back as:"))
            (when (contains? siblings-slot msg-name)
              (sibling-fn (str "proto->" msg-name "--slot-map") :slot-read
                          "[msg]" "msg"
                          "as a plain map, read from the compiled arm's slots:"))))))


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

(defn- resolve-files
  "Every file in the request as a built FileDescriptor, keyed by proto path.

  This is where feature resolution happens — in protobuf-java, not here. It is
  the same delegation the emitted file makes at load time, and the only way to
  answer the questions the typed read path asks: does this field have presence,
  is this enum open, is this message field really a group. One pass suffices
  because protoc sends proto_file in topological order.

  A file that will not build gets no entry and simply loses that path. That is
  not a hypothetical: --descriptor_set_in accepts sets assembled by hand, and a
  file whose dependency failed cannot be built either. Nothing else in emission
  depends on this, so a miss costs speed and nothing more."
  [^PluginProtos$CodeGeneratorRequest req]
  (reduce (fn [acc ^DescriptorProtos$FileDescriptorProto fdp]
            (let [deps (mapv acc (.getDependencyList fdp))]
              (if (some nil? deps)
                acc
                (try
                  (assoc acc (.getName fdp)
                         (Descriptors$FileDescriptor/buildFrom
                          fdp (into-array Descriptors$FileDescriptor deps)))
                  (catch Throwable _ acc)))))
          {}
          (.getProtoFileList req)))

(defn generate
  "CodeGeneratorRequest -> CodeGeneratorResponse."
  ^PluginProtos$CodeGeneratorResponse [^PluginProtos$CodeGeneratorRequest req]
  (let [params    (parse-params (.getParameter req))
        prefix    (when (string? (:ns_prefix params)) (:ns_prefix params))
        keep-src? (flag? (:keep_source_info params))
        interop?  (flag? (:interop params))
        rt-ns     (runtime-namespaces params)
        to-gen    (set (.getFileToGenerateList req))
        generated? #(contains? to-gen %)
        ;; Both typed read paths consult these, and the compiled arm's is on by
        ;; default, so this is no longer conditional. It is the one piece of work
        ;; in this fn proportional to the whole request rather than to the files
        ;; asked for.
        resolved  (resolve-files req)
        resp      (PluginProtos$CodeGeneratorResponse/newBuilder)]
    (doseq [^DescriptorProtos$FileDescriptorProto fdp (.getProtoFileList req)
            :when (to-gen (.getName fdp))]
      (let [ns-name (proto->ns (.getName fdp) prefix)]
        (.addFile resp (-> (PluginProtos$CodeGeneratorResponse$File/newBuilder)
                           (.setName (ns->path ns-name))
                           (.setContent (emit-namespace fdp generated? prefix keep-src? rt-ns interop?
                                                        (get resolved (.getName fdp))))
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
  "0.7.0")

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
