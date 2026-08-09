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
            DescriptorProtos$ServiceDescriptorProto
            Descriptors$FileDescriptor]
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
        msgs    (mapv (fn [^DescriptorProtos$DescriptorProto md]
                        {:name   (.getName md)
                         :fields (mapv (fn [^DescriptorProtos$FieldDescriptorProto f]
                                         {:proto-name (.getName f)
                                          :key        (field-key-symbol (.getName f))})
                                       (.getFieldList md))})
                      (.getMessageTypeList fdp))
        ;; The type hint is load-bearing, not tidiness. Unhinted, this compiles to
        ;; a reflective call that works fine on the JVM and fails in the native
        ;; image, where no reflection metadata is registered:
        ;;   No matching field found: getName for class ServiceDescriptorProto
        ;; Every interop call here must be hinted for the same reason.
        svcs    (mapv #(.getName ^DescriptorProtos$ServiceDescriptorProto %)
                      (.getServiceList fdp))
        ;; Nested types get no record yet (issue #2). Emitting nothing at all
        ;; would be silently incomplete — the file generates fine and `->Inner`
        ;; simply does not exist — so name them in the output instead.
        ;;
        ;; map<k,v> fields synthesise a nested *Entry message with
        ;; options.map_entry set. Those are an encoding detail and must not be
        ;; listed, or every proto with a map would grow a misleading notice.
        skipped (vec (for [^DescriptorProtos$DescriptorProto md (.getMessageTypeList fdp)
                           ^DescriptorProtos$DescriptorProto n (.getNestedTypeList md)
                           :when (not (.getMapEntry (.getOptions n)))]
                       (str (.getName md) "." (.getName n))))
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
      (doseq [{msg-name :name fields :fields} msgs]
        (line "")
        (line (str "(defrecord " msg-name " [" (str/join " " (map :key fields)) "])"))
        (line (str "(def " msg-name "-prototype (rt/message file-descriptor "
                   (pr-str msg-name) "))"))
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


    (when (seq skipped)
      (line "")
      (line ";; NOT GENERATED — nested messages are not yet emitted as records:")
      (doseq [n skipped]
        (line (str ";;   " n)))
      (line ";; The enclosing message still round-trips; only the convenience")
      (line ";; record and ->/<- functions for these types are missing."))

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
  "0.1.0")

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
