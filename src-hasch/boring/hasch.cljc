(ns boring.hasch
  "Make an unregistered tag-27 frame hash as the value it stands for.

  **Optional.** boring's only runtime dependency is Clojure; this namespace is
  in its own source root and is loaded only if you `:require` it, in which case
  you supply `org.replikativ/hasch` yourself. Nothing in `boring.core` refers
  to it.

  ## The problem it fixes

  `hasch` content-addresses by walking a value's structure. It knows about
  records and about `incognito.base.IncognitoTaggedLiteral`, hashing both as
  `[tag value]`. It does not know boring's fallbacks, and `UnknownRecord`
  implements `IPersistentMap` -- so hasch walked it as a **bare map** and
  dropped the type name entirely:

      {:x 3 :y 4}                              2194b816-...
      unknown-record \"user.Point\" {:x 3 :y 4}  2194b816-...   same
      unknown-record \"other.Type\" {:x 3 :y 4}  2194b816-...   same

  Three distinct values, one content address. In a content-addressed store that
  is a collision, and it is silent -- and worse, a peer that HAS the record
  class computes a different address from one that does not, so the same
  logical value lands at two addresses depending on the classpath.

  With this namespace loaded, all four agree: the real record, an
  `IncognitoTaggedLiteral`, an `UnknownRecord` and a `TaggedLiteral` for the
  same type and fields hash identically."
  (:require [boring.data :as data]
            [hasch.benc :as benc]
            [hasch.platform :as platform]))

(defn- literal-hash
  "hasch's encoding for a tagged value: the :literal magic over [tag value].
  Matches `incognito-writer`, whose tag is the type name as a symbol."
  [tag value md-create-fn write-handlers]
  (platform/encode (:literal benc/magics)
                   (benc/coerce-seq [tag value] md-create-fn write-handlers)))

#?(:clj
   (extend-protocol benc/PHashCoercion
     boring.data.UnknownRecord
     (-coerce [this md-create-fn write-handlers]
       (literal-hash (symbol (data/frame-name this))
                     (data/frame-payload this)
                     md-create-fn write-handlers))

     clojure.lang.TaggedLiteral
     (-coerce [this md-create-fn write-handlers]
       (literal-hash (:tag this) (:form this) md-create-fn write-handlers)))

   :cljs
   (extend-protocol benc/PHashCoercion
     data/UnknownRecord
     (-coerce [this md-create-fn write-handlers]
       (literal-hash (symbol (data/frame-name this))
                     (data/frame-payload this)
                     md-create-fn write-handlers))

     TaggedLiteral
     (-coerce [this md-create-fn write-handlers]
       (literal-hash (:tag this) (:form this) md-create-fn write-handlers))))
