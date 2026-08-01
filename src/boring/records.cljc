(ns boring.records
  "Build a record registry without naming every record by hand.

  Two mechanisms, because the platforms differ in what is knowable when:

  - **`auto-registry` (macro, both platforms)** resolves at COMPILE time. It
    asks the compiler which records exist and emits a literal map of wire name
    to constructor. Nothing is resolved from wire content at run time, so the
    set of constructible types is fixed when you build. This is the only
    mechanism that can work on ClojureScript, and it is the safer one on the
    JVM too.

  - **`{:auto-construct-records? true}` (JVM only, a decode option)** resolves
    at run time from the class name on the wire. Use it when the record types
    are not known at build time -- a plugin, a REPL, a dynamically loaded
    namespace. See doc/SECURITY.md for exactly what it relaxes.

  The compile-time route is preferred wherever it fits."
  #?(:cljs (:require-macros [boring.records]))
  ;; `boring.core` is used only inside the macro's syntax quote, where the
  ;; `boring/` alias resolves to `boring.core/` at expansion time. clj-kondo
  ;; cannot see that through a reader conditional, so it reports the require as
  ;; unused; it is not, and dropping it would break alias resolution.
  #_{:clj-kondo/ignore [:unused-namespace]}
  (:require [boring.core :as boring]
            #?(:clj [clojure.string :as str])))

;; `cljs.env` is deliberately NOT required at the top. It exists only while
;; ClojureScript is compiling, and requiring it unconditionally makes this
;; namespace unloadable on a plain JVM classpath -- which is most of them.

#?(:clj
   (defn- wire-name
     "The name a record of `record-sym` in `ns-sym` carries on the wire.

     Must match `boring.data/record-type-name`, which munges `-` to `_` so the
     JVM class name and the ClojureScript `pr-str` name agree."
     [ns-sym record-sym]
     (str/replace (str ns-sym "." record-sym) "-" "_")))

#?(:clj
   (defn- cljs-records
     "[[ns-sym record-sym] ...] for every defrecord the ClojureScript compiler
     has analysed. A defrecord defines `map->Name` in its namespace, so the
     compiler's analysis cache lists it and no runtime lookup is needed."
     [compiler-env]
     (for [[ns-sym ns-data] (:cljs.analyzer/namespaces compiler-env)
           [def-sym _] (:defs ns-data)
           :let [n (name def-sym)]
           :when (str/starts-with? n "map->")]
       [ns-sym (symbol (subs n 5))])))

#?(:clj
   (defn- clj-records
     "The same for Clojure, from the loaded namespaces."
     []
     (for [n (all-ns)
           [sym _] (ns-publics n)
           :let [s (name sym)]
           :when (str/starts-with? s "map->")
           :when (try (Class/forName (wire-name (ns-name n) (subs s 5)))
                      (catch Throwable _ false))]
       [(ns-name n) (symbol (subs s 5))])))

#?(:clj
   (defmacro registry-for
     "A registry for the records in exactly these namespaces. Deterministic.

         (records/registry-for my.app.model my.app.events)

     Prefer this over `auto-registry` when it matters what the registry
     contains. On the JVM `auto-registry` scans namespaces that are LOADED when
     it expands, and loading is global: a namespace pulled in by something
     unrelated is visible to a caller that never required it, so the same
     source can produce different registries in a REPL and in an AOT build.
     This arity names its inputs, and on the JVM requires them first, so the
     answer does not depend on what else happened to be loaded.

     On ClojureScript the named namespaces must be required by the calling
     namespace as usual -- the macro reads the compiler's analysis cache and
     cannot cause a namespace to be analysed."
     [& ns-syms]
     (let [cljs? (some? (:ns &env))
           _     (when-not cljs? (doseq [n ns-syms] (require n)))
           wanted (set (map str ns-syms))
           pairs (if cljs?
                   (cljs-records @@(requiring-resolve 'cljs.env/*compiler*))
                   (clj-records))
           entries (for [[ns-sym rec-sym] pairs
                         :when (contains? wanted (str ns-sym))]
                     [(wire-name ns-sym rec-sym)
                      (symbol (str ns-sym) (str "map->" rec-sym))])]
       `(reduce (fn [reg# [nm# ctor#]] (boring/register-record reg# nm# ctor#))
                (boring/tag-registry)
                ~(vec (for [[nm ctor] entries] [nm ctor]))))))

#?(:clj
   (defmacro auto-registry
     "A registry that can reconstruct every defrecord the compiler knows about.

     Resolved at COMPILE time and emitted as a literal map, so it works under
     ClojureScript advanced compilation -- where constructor names are minified
     and there is no runtime `resolve` -- and performs no lookup driven by wire
     content on either platform.

         (def registry (boring/auto-registry))
         (boring/decode bs {:registry registry})

     `prefix` is a literal string, for narrowing to your own namespaces:

         (boring/auto-registry \"my.app\")

     A literal rather than a predicate function on purpose: the macro would
     have to `eval` a function to apply it at expansion time, and during
     ClojureScript macroexpansion `*ns*` is not a namespace where that is
     reliable: a predicate function failed to resolve `=` at expansion time.

     Records defined AFTER this expands are not included; that is the trade for
     resolving nothing at run time. On the JVM, `{:auto-construct-records?
     true}` covers the dynamic case.

     **This sees more than your require graph.** On the JVM it sees namespaces
     LOADED when it expands, and loading is global -- a namespace pulled in by
     something unrelated is visible to a caller that never required it, so the
     same source can yield different registries in a REPL and in an AOT build.
     On ClojureScript it reads the compiler's analysis cache, which holds every
     namespace in the BUILD, so the no-prefix form picks up records from
     namespaces the caller never mentions.

     The prefix is what makes the result predictable. `registry-for` names its
     inputs exactly; prefer it when the contents matter."
     ([] `(auto-registry ""))
     ([prefix]
      (assert (string? prefix)
              "boring/auto-registry takes a literal namespace prefix string")
      (let [cljs?   (some? (:ns &env))
            pairs   (if cljs?
                      (cljs-records @@(requiring-resolve 'cljs.env/*compiler*))
                      (clj-records))
            entries (for [[ns-sym rec-sym] pairs
                          :when (str/starts-with? (str ns-sym) prefix)]
                      [(wire-name ns-sym rec-sym)
                       (symbol (str ns-sym) (str "map->" rec-sym))])]
        `(reduce (fn [reg# [nm# ctor#]] (boring/register-record reg# nm# ctor#))
                 (boring/tag-registry)
                 ~(vec (for [[nm ctor] entries] [nm ctor])))))))
