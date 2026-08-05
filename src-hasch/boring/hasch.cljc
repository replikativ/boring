(ns boring.hasch
  "Make an unregistered tag-27 frame hash as the value it stands for.

  **Optional, and it activates itself.** boring's only runtime dependency is
  Clojure; this namespace lives in its own source root (`src-hasch`) and hasch
  stays out of `:deps`, which is also a licence boundary -- hasch is EPL-1.0
  and boring is Apache-2.0. You supply `org.replikativ/hasch` yourself.

  But you do NOT have to require this namespace. `boring.core` ends with a
  `defonce` whose body is `(require 'boring.hasch)`, catching only \"namespace
  not found\", so requiring `boring.core` on a classpath that has both hasch and
  `src-hasch` loads this and sets `boring.core/hasch-integration?` to true:

      clojure -Sdeps '{:paths [\"src\" \"src-hasch\" \"target/classes\"]
                       :deps {org.replikativ/hasch {:mvn/version \"0.4.100\"}}}' \\
        -M -e \"(require '[boring.core :as b]) (println b/hasch-integration?)\"
      ;; => true

  Automatic rather than left to the consumer because forgetting it is not a
  loud failure: hashes simply come out wrong, and only when two peers disagree
  about whether the record class is present does anyone notice. This paragraph
  used to say \"Nothing in `boring.core` refers to it\", which predates that
  auto-load -- and it is the paragraph someone reads while deciding whether
  their content addresses are right.

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
            [clojure.string :as str]
            [hasch.benc :as benc]
            [hasch.platform :as platform]))

(defn- hash-tag
  "boring's wire name as the symbol hasch and incognito hash under.

  THE WIRE NAME AND THE HASH NAME ARE DIFFERENT THINGS, and conflating them is
  what broke here. boring's wire name is the record's own `namespace/Name`, as
  written, because the format can afford to be lossless. hasch coerces a LIVE
  record through its class name, and incognito's `incognito-writer` does
  `(-> r type pr-str normalize-ns symbol)` -- `/` to `.`, then `-` to `_`. Both
  land on the munged, dotted form.

  So the same value hashed with the record class present and hashed without it
  diverged the moment boring's wire name stopped being the class name: exactly
  the invariant this namespace exists to hold. Translating here keeps content
  addresses stable across that change and keeps them equal to incognito's,
  which is the other half of the promise.

  This is `incognito.base/normalize-ns`, reimplemented rather than depended on
  -- boring's only runtime dependency is Clojure."
  [n]
  (symbol (-> (str n)
              (str/replace-first "/" ".")
              (str/replace "-" "_"))))

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
       (literal-hash (hash-tag (data/frame-name this))
                     (data/frame-payload this)
                     md-create-fn write-handlers))

     clojure.lang.TaggedLiteral
     (-coerce [this md-create-fn write-handlers]
       (literal-hash (:tag this) (:form this) md-create-fn write-handlers)))

   :cljs
   (extend-protocol benc/PHashCoercion
     data/UnknownRecord
     (-coerce [this md-create-fn write-handlers]
       (literal-hash (hash-tag (data/frame-name this))
                     (data/frame-payload this)
                     md-create-fn write-handlers))

     TaggedLiteral
     (-coerce [this md-create-fn write-handlers]
       (literal-hash (:tag this) (:form this) md-create-fn write-handlers))))
