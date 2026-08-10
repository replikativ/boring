(ns boring.handler-failure-test
  "A registered handler that throws must not defeat the caller's `catch`.

  WHAT WAS WRONG. boring invokes four kinds of caller-supplied function -- a
  registered tag reader, a registered record constructor, a registered tag
  writer, and `:encode-fallback` -- and every one of them was invoked
  unguarded. Whatever they threw came straight out of `encode` or `decode`:

      registered record ctor, payload 42        IllegalArgumentException
      registered tag reader, payload 42         ClassCastException
      registered tag reader, \":://not a uri\"    URISyntaxException
      registered tag writer that throws         RuntimeException

  That is guarantee 3 of doc/SECURITY.md -- \"Nothing escapes as a raw
  NullPointerException, ClassCastException or StackOverflowError, so a caller's
  `catch ExceptionInfo` is sufficient\" -- and it is the \"error-handling
  bypass\" its own list of realistic harms names fourth.

  THE INTERESTING CASE IS NOT A BUGGY HANDLER. It is a CORRECT one:
  `java.net.URI` is right to throw on `\":://not a uri\"`, and whoever wrote the
  bytes chose that string. doc/SECURITY.md calls installed handlers trusted,
  but that is about their PRIVILEGES -- what they may do -- not about whether
  their failure on hostile input stays typed. The input is untrusted by
  definition.

  It was found via the twelve reserved record names, where boring hands a
  caller's constructor a payload shaped for its own built-in. That made it easy
  to hit and looked like a reserved-name problem; it is not. An ordinary name
  reproduces it.

  WHY NOTHING CAUGHT IT. `boring.hostile` feeds malformed content to every
  BUILT-IN tag and asserts a typed failure -- it exists because five built-in
  handlers once leaked exactly these exception types. Registered handlers never
  got the same treatment, because that suite enumerates built-ins. This file is
  the registered-handler half."
  (:require [clojure.test :refer [deftest is testing]]
            [boring.core :as boring]
            [boring.data :as data]))

(defrecord Pt [x y])

(defn- outcome
  "`:typed <type>` or `:raw <class>` for whatever `f` throws, `:ok` otherwise."
  [f]
  (try (do (f) :ok)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (if-let [t (:type (ex-data e))]
           [:typed t]
           [:raw (pr-str e)]))
       #?(:clj (catch Throwable e [:raw (.getSimpleName (class e))]))))

(defn- tagged-bytes
  "Bytes carrying `(tagged-value tag payload)`, written with no registry so the
  handler is only reached on the way back in."
  ^{:tag #?(:clj 'bytes :cljs 'js/Uint8Array)} [tag payload]
  (boring/encode (data/tagged-value tag payload) {:stringref false}))

;; ------------------------------------------------------------------ read side

(deftest a-record-constructor-that-throws-is-typed
  (testing "an ORDINARY wire name, so this is not about the reserved twelve"
    (let [reg (boring/register-record (boring/tag-registry) "my.app/point"
                                      (fn [m] (map->Pt m)))
          bs (tagged-bytes 27 ["my.app/point" 42])]
      (is (= [:typed :boring/handler-failed]
             (outcome #(boring/decode bs {:registry reg})))))))

(deftest a-tag-reader-that-throws-is-typed
  (let [reg (boring/register-tag (boring/tag-registry) 4242
                                 #?(:clj java.net.URI :cljs js/String)
                                 str
                                 (fn [s] #?(:clj (java.net.URI. s)
                                            :cljs (if (re-find #"^\w+:" s)
                                                    s
                                                    (throw (js/Error. "bad uri"))))))]
    (testing "handed a payload of the wrong type"
      (is (= [:typed :boring/handler-failed]
             (outcome #(boring/decode (tagged-bytes 4242 42) {:registry reg})))))
    (testing "and handed a well-typed payload it legitimately rejects -- the
              case that matters, because the handler is CORRECT and the bytes
              are the attacker's"
      (is (= [:typed :boring/handler-failed]
             (outcome #(boring/decode (tagged-bytes 4242 ":://not a uri")
                                      {:registry reg})))))))

(deftest a-handlers-own-typed-error-passes-through-unchanged
  (testing "a handler raising `(ex-info ... {:type :my.app/...})` is reporting
            something its caller wants to catch SPECIFICALLY. Rewrapping that
            in :boring/handler-failed would bury it, so only an untyped throw
            is converted."
    (let [reg (boring/register-record (boring/tag-registry) "my.app/typed"
                                      (fn [_] (throw (ex-info "mine" {:type :my.app/bad-point}))))
          bs (tagged-bytes 27 ["my.app/typed" {"a" 1}])]
      (is (= [:typed :my.app/bad-point]
             (outcome #(boring/decode bs {:registry reg})))))))

;; ----------------------------------------------------------------- write side

(deftest a-tag-writer-that-throws-is-typed
  (testing "less exposed than the read side -- the value is the caller's own --
            but the same guarantee, and the same one-line hole"
    (let [reg (boring/register-tag (boring/tag-registry) 4243 Pt
                                   (fn [_] (throw (#?(:clj RuntimeException. :cljs js/Error.) "boom")))
                                   identity)]
      (is (= [:typed :boring/handler-failed]
             (outcome #(boring/encode (->Pt 1 2) {:registry reg})))))))

(deftest an-encode-fallback-that-throws-is-typed
  (testing "`:encode-fallback` is caller code invoked by the writer on a value
            it has no encoding for, which is the same shape again"
    (is (= [:typed :boring/handler-failed]
           (outcome #(boring/encode (fn [] 1)
                                    {:encode-fallback
                                     (fn [_] (throw (#?(:clj RuntimeException. :cljs js/Error.) "boom")))}))))))

;; ------------------------------------------------- the reserved names, in passing

#?(:clj
   (deftest the-reserved-record-names-now-fail-typed
     (testing "registering under one of boring's own twelve wire names stays
               ALLOWED -- `Reader` checks the built-in markers after the
               registry precisely so a caller can take these names -- but
               boring then hands that constructor a payload shaped for its own
               built-in, and 10 of the 12 threw raw. They are the reason this
               hole was found; they are not the hole."
       (doseq [[nm v] [["clojure/sorted-set" (into (sorted-set) [1 2])]
                       ["clojure/char" \a]
                       ["clojure/queue" (into clojure.lang.PersistentQueue/EMPTY [1 2])]
                       ["java/period" (java.time.Period/ofDays 3)]
                       ["java/object-array" (object-array [1 "a"])]]]
         (let [reg (boring/register-record-class (boring/tag-registry) Pt nm)
               bs (boring/encode v {:stringref false})
               [kind t] (outcome #(boring/decode bs {:registry reg}))]
           (is (= :typed kind) (str nm " -> " t)))))))
