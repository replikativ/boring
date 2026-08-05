# Extending

boring handles every Clojure type without registration. This document is for
the cases it cannot know about: your own types, and record types you want
reconstructed rather than left inert.

## Registries are values

A registry is an immutable value on **both** platforms. Registration returns a
new one; you thread it and pass it as `:registry`.

```clojure
(require '[boring.core :as boring])

(def registry
  (-> (boring/tag-registry)
      (boring/register-tag 40001 java.net.URI str #(java.net.URI. %))))

(boring/encode (java.net.URI. "https://example.com") {:registry registry})
(boring/decode bytes {:registry registry})
```

There is deliberately **no process-global default registry**. If there were,
two libraries in one JVM — say a database and a storage backend — could both
register the same tag, and which one won would depend on namespace load order.
Silently. Passing `:registry` explicitly makes that impossible.

## Custom tags

```clojure
(boring/register-tag reg tag cls write-fn read-fn)
```

- `write-fn` — `(fn [value] -> encodable)`; the result is written as the tag's
  content, so return something boring already knows how to write.
- `read-fn` — `(fn [decoded-content] -> value)`.

Either may be `nil`. Registering only `read-fn` lets you consume a tag you
never produce; registering only `write-fn` produces one you do not consume.

Pick tag numbers from IANA's First-Come-First-Served range (**≥ 32768**) and
[register them][iana] if the data leaves your own systems. Numbers below that
are allocated by specification, and taking one means your documents disagree
with the standard.

Without a `read-fn`, an incoming tag decodes to an inert
`boring.data/TaggedValue` carrying the tag number and content — inspectable,
and re-encodable. Nothing is lost and nothing is guessed.

An unknown tag's value is preserved SEMANTICALLY, not byte-for-byte. A
`TaggedValue` holds the tag number and the decoded content, not the original
encoding, so a non-preferred input normalises on the way out: the overlong
`da0000ffff 01` re-encodes as `d9ffff 01`, and indefinite lengths become
definite. The tag and the value survive exactly; the encoding of them becomes
boring's preferred form. Tag numbers are supported across the full unsigned
64-bit range, so a foreign encoder's `2^64-1` round-trips rather than
overflowing.

### Handlers beat structure

A registration wins over boring's built-in encoding for that exact class, even
when the type also happens to be a map, set or collection. That is worth
knowing because the alternative is a trap: a handler that is silently ignored
because your type implements `java.util.Set` looks exactly like a handler that
works, right up until you read the bytes back.

## Records

Records already round-trip *as themselves* with no registration — boring writes
the type name via CBOR tag 27 — so the type is never flattened into a plain map.
This is the problem [incognito][] exists to solve for fressian, and boring does
not have it.

Registration only affects **reading**. Without it, a record decodes to a
`boring.data/UnknownRecord` carrying the same name and fields, which re-encodes
to identical bytes:

```clojure
(defrecord Point [x y])

(def registry
  (-> (boring/tag-registry)
      (boring/register-record "my.ns/Point" map->Point)))
```

The wire name is `boring.data/record-type-name` of an instance: `namespace/Name`
**as written**, on both platforms, with nothing munged. So **one registration
serves data written on either platform**.

That sentence used to say the JVM wrote the class name and ClojureScript munged
its own name to match. It no longer does, and the difference is not cosmetic:
Clojure's `namespace-munge` turns `-` into `_` when it builds a record's class
name, so the JVM had *lost* the namespace as written and boring made the
browser discard information it still had in order to agree. The JVM now inverts
that munge by looking the namespace up rather than guessing.

### Prefer a registration that derives the name

A hand-written name string is the one part of this that can silently stop
matching, because a registry that never matches looks exactly like data with no
constructor: the record simply arrives as an `UnknownRecord`, with no error.
That is not hypothetical — it is precisely how the change above went unnoticed
in boring's own nippy suite, which registered `taoensso.nippy.StressRecord`
while the wire had moved to `taoensso.nippy/StressRecord`.

Both drift-proof forms derive the name from the type itself, so a change to the
naming rule cannot leave them behind:

```clojure
(records/auto-registry "my.app")            ; both platforms, compile time
(boring/register-record-class reg Point)    ; JVM, reflective
```

Use these where you can. When you must write the name by hand — `.cljc` code
registering a ClojureScript record, most often — `{:on-unknown-record :error}`
turns the silence into a typed failure:

```clojure
(boring/decode bs {:registry reg :on-unknown-record :error})
;; => :boring/unregistered-record, rather than an UnknownRecord you did not expect
```

That is worth doing in tests even if production keeps the default, since the
default exists to let a value pass through a peer that has no constructor for
it — see [Records you cannot construct](#records-you-cannot-construct-and-why-that-is-the-point).

A handful of tag-27 names are reserved by boring for types CBOR cannot
otherwise distinguish — `clojure/sorted-map`, `clojure/sorted-set`,
`clojure/queue`, `clojure/with-meta`, `clojure/char`, `java/period`. They carry
a slash separating the owning runtime from the type — and so, since this
release, does every record name: `my-ns/MyRecord`. The old claim here was that
a slash made collision impossible; it does not any more, and the honest
statement is narrower. A collision needs a record named `sorted-map` in a
namespace named `clojure`, which nothing writes by accident, and the registry
wins anyway. If you want one of those names for yourself, take it: the
registry is consulted **before** the built-in markers, both when reading and
when writing. See [COMPATIBILITY.md](COMPATIBILITY.md).

That ordering was a bug first. The registry used to be consulted *after* the
built-in handling on both sides, so registering a tag for a type boring already
knew about was silently ignored — the registration compiled, returned a
registry, and did nothing.

On the JVM there is a reflective convenience that derives both the name and the
`map->` constructor:

```clojure
(boring/register-record-class reg Point)
```

It is JVM-only — advanced compilation minifies ClojureScript constructor names,
so there is nothing to reflect on. Use `register-record` in `.cljc`.

## Portable registration

`register-tag` and `register-record` have identical signatures on both
platforms and both return the registry, so registration code lives in a `.cljc`
file with a reader conditional only around the *type* being registered:

```clojure
(def registry
  (-> (boring/tag-registry)
      (boring/register-tag 40001
                           #?(:clj java.net.URI :cljs js/URL)
                           #?(:clj str :cljs #(.-href %))
                           #?(:clj #(java.net.URI. %) :cljs #(js/URL. %)))
      (boring/register-record "my.ns/Point" map->Point)))
```

Thread the return value. On the JVM an earlier design mutated in place, which
meant registration code that ignored the return value worked there and silently
did nothing on ClojureScript — it compiled either way. Both sides are values
now, so that trap is gone.

## Records you cannot construct, and why that is the point

The default for an unregistered record — an `UnknownRecord` rather than an
error — is the design decision this section exists to argue for, because it is
what lets record types survive a network of peers that do not all share a
classpath.

**The problem it solves.** In a peer-to-peer or store-and-forward Clojure
system, a value is written by one process, relayed or cached by several that
have never heard of its types, and read by another that has them. The usual
outcome is that the type is lost at the first hop: a serializer that flattens
records to maps destroys the name immediately, and one that *errors* on an
unknown type makes the middle peers refuse to handle the data at all. Either
way the far end cannot get the record back, and the middle is forced to know
about types that are none of its business.

boring's answer is that the type name travels **in the data**, as CBOR tag 27,
and a peer that cannot construct the type still holds the name and the fields:

```
    peer A                    peer B                     peer C
 has my.app/Point          no classpath entry        has my.app/Point
       │                          │                          │
   #my.app/Point{:x 1}   ──►  UnknownRecord      ──►    #my.app/Point{:x 1}
                              "my.app/Point"
                              {:x 1}
                                   │
                            stores it, indexes it,
                            re-encodes it — byte-identical
```

Peer B needs no registration, no schema and no code generation. It can store
the value, put it in a queue, content-address it, hand it back later — and what
comes out is the same bytes that went in, so C reconstructs the record C's
classpath knows about. The middle peer participates without being coupled.

This is what [incognito][] exists to provide for fressian. boring does not need
a companion library for it because the name is in the format.

**And it stays a map the whole time.** An `UnknownRecord` is not an opaque box
you have to unwrap: it implements the map interfaces, so peer B's ordinary
Clojure code works on it.

```clojure
(def u (boring/decode bs))          ; no registry
(:x u)                              ; => 1
(get u :x)                          ; => 1
(keys u)                            ; => (:x :y)
(count u)                           ; => 2
(map? u)                            ; => true
(data/frame-name u)                 ; => "my.app/Point"
```

### Does manipulating it keep the type?

Yes, for everything that builds *from* the record. Measured, identical on both
platforms:

| operation | result | re-encodes as |
|---|---|---|
| `assoc`, `assoc-in`, `update`, `update-in` | `UnknownRecord` | its tag-27 frame |
| `dissoc`, `dissoc` of every key | `UnknownRecord` | its tag-27 frame |
| `conj`, `merge` *onto* it, `into` it | `UnknownRecord` | its tag-27 frame |
| `empty`, `with-meta` | `UnknownRecord` | its tag-27 frame |
| `select-keys`, `into {}`, `merge` *into a map* | **plain map** | a plain map — type gone |

So a middle peer can enrich or prune a record it has never seen and the far end
still gets a record.

The bottom row is not a boring limitation — it is Clojure's, and **a real
`defrecord` behaves the same way**. `select-keys`, `into {}` and
`(merge {} r)` all build a fresh map and cannot preserve any type. Measured
side by side, `UnknownRecord` matches `defrecord` at every one of those points
and is *more* forgiving at two:

| operation | `defrecord` | `UnknownRecord` |
|---|---|---|
| `dissoc` of a basis field | degrades to a plain map | keeps the type |
| `empty` | **throws** `UnsupportedOperationException` | empty record of the same type |

The rule to carry: **if the operation would keep a `defrecord` a record, it
keeps an `UnknownRecord` an `UnknownRecord`.** Code written against real
records behaves the same when the record turns out to be unregistered, which is
what makes the middle-peer story work without special cases.

### Equality is the one place to be careful

`UnknownRecord` follows `defrecord` equality: equal to another of the **same
wire type with the same fields**, and *not* equal to a bare map with those
fields. Two frames with different type names and identical fields are not
equal.

Comparing one against a plain map is **asymmetric on ClojureScript** and does
not agree across platforms. Measured, the same expression on both:

```clojure
[(= u {:a 1}) (= {:a 1} u) (contains? #{{:a 1}} u) (contains? #{u} {:a 1})]
;; JVM   [false false false false]
;; CLJS  [false true  false true ]
```

ClojureScript's `equiv-map` excludes only *real* records, and `UnknownRecord`
satisfies `IMap`, so the map's own `-equiv` accepts it while the record's does
not. `hash` disagrees in both directions on both platforms, so set membership
follows whichever side you put it on. **Do not rely on `=` between an
`UnknownRecord` and a plain map in portable code** — compare `frame-name` and
`frame-payload`, or register the type.

### When you would rather it failed

The passthrough is a default, not a policy. A peer that *should* have every
type on its classpath can say so:

```clojure
(boring/decode bs {:registry reg :on-unknown-record :error})
```

See [`:on-unknown-record`](#prefer-a-registration-that-derives-the-name) above;
it also takes a function, which is how you warn without boring choosing a
logging library for you.

## Reconstructing records without registering them

Two mechanisms, because the platforms differ in what is knowable when.

### `auto-registry` — compile time, both platforms *(preferred)*

```clojure
(require '[boring.records :as records])

(def registry (records/auto-registry "my.app"))   ; literal ns prefix
(boring/decode bs {:registry registry})
```

A macro. It asks the compiler which records exist and emits a **literal map**
of wire name to constructor, so nothing is resolved from wire content at run
time and the constructible set is fixed when you build.

This is the only mechanism that can work on ClojureScript — advanced
compilation minifies constructor names and there is no runtime `resolve` — and
it works there because the constructors are compile-time links rather than
name lookups. Verified under `-O advanced`: the record type's own name minifies
to two characters and reconstruction still succeeds.

It is also the safer choice on the JVM. Records defined *after* the macro
expands are not included; that is the trade for resolving nothing at run time.

#### Load order

`auto-registry` sees namespaces that are **loaded when it expands**. For a
namespace that requires what it needs, that is exactly right:

```clojure
(ns my.app.serialization
  (:require [my.app.model]              ; the records
            [boring.records :as records]))

(def registry (records/auto-registry "my.app"))   ; sees my.app.model
```

A namespace that requires nothing sees nothing — verified: a caller that did
not require the record namespace decoded to `UnknownRecord`, and one that did
reconstructed the record.

**Both platforms see more than your require graph, and that is the sharp
edge.** On the JVM, loading is global: a namespace pulled in by something
unrelated is visible to a caller that never required it, so the same source can
produce different registries in a REPL and in an AOT build. On ClojureScript
the compiler's analysis cache holds every namespace in the **build**, so a
no-prefix `auto-registry` picks up records from namespaces the calling
namespace never mentions — measured, with a record from an unrequired
namespace reconstructing through a registry built elsewhere.

The prefix is therefore not tidiness; it is what makes the result predictable.
When the contents matter, name them instead:

```clojure
(def registry (records/registry-for my.app.model my.app.events))
```

`registry-for` contains exactly those namespaces' records and nothing else. On
the JVM it requires them first, so the answer does not depend on load order at
all.

### `{:auto-construct-records? true}` — run time, JVM only

```clojure
(boring/decode bs {:auto-construct-records? true})
```

For record types not known at build time: a plugin, a REPL, a dynamically
loaded namespace. **Off by default, and the default is the security posture** —
read [SECURITY.md](SECURITY.md) first. Resolution uses
`RT/classForNameNonLoading`, so naming a class does **not** run its static
initialiser, and the class must also be an `IRecord` with a static
`create(IPersistentMap)`.

Refused with `:boring/unsupported-option` on ClojureScript rather than silently
ignored, so a `.cljc` codebase cannot decode to real records on one platform
and fallbacks on the other.

## Content-addressing with hasch

`hasch` walks a value's structure, and an unregistered frame is not a shape it
knows: `UnknownRecord` implements `IPersistentMap`, so hasch hashed it as a
bare map and dropped the type name. `user.Point`, `other.Type` and a plain
`{:x 3 :y 4}` all produced **one address**, and a peer holding the record class
addressed the same value differently from one that did not.

`boring.hasch` fixes both, and **you do not have to require it**: `boring.core`
loads it automatically when hasch is on the classpath. Check
`boring.core/hasch-integration?` if you want to be sure.

It is still optional and in its own source root — boring's only runtime
dependency is Clojure, and hasch is EPL-1.0 while boring is Apache-2.0 — so
nothing happens if you do not depend on hasch. The auto-load exists because
forgetting it is not a loud failure: hashes simply come out wrong, and only a
disagreement between two peers reveals it.

With it loaded, a record, an `incognito` tagged literal, an `UnknownRecord` and
a `TaggedLiteral` for the same type and fields all hash identically.

## When a value has no encoding

By default one unencodable value aborts the whole document. On a wire that is
usually the wrong trade — a message that arrives with a placeholder beats a
message that does not arrive:

```clojure
(boring/encode v {:encode-fallback :placeholder})   ; built-in placeholder
(boring/encode v {:encode-fallback (fn [x] :redacted)})
```

`:placeholder` writes `27(["boring/unencodable", {:type … :repr …}])`, which is
readable by any CBOR implementation and obviously not the original value. A
function receives the offending value and returns a replacement.

A fallback returning something *also* unencodable throws rather than looping.

## Reading a stream larger than memory

```clojure
(with-open [in (io/input-stream "dump.cbor")]
  (doseq [item (boring/decode-seq-from in)] ...))
```

Bounded by the largest single **item** plus the chunk size (`:chunk-size`,
64 KiB default), not by the stream — verified by decoding a 27 MB file in a
20 MB heap. That limit is not a compromise: an item has to fit in memory to be
a Clojure value at all, so streaming can only ever mean a sequence of items,
which is exactly what a dump is.

The reader's hot path is untouched; refilling happens between items, so
decoding from a `byte[]` costs nothing extra.

## Security

Registered callbacks run with your process's privileges. boring does not
sandbox them; vet what you install.

What a hostile document *cannot* do is cause an arbitrary class to be
instantiated. Reading dispatches on a tag **number** or a record **name** looked
up in your registry — there is no `Class.forName` path, no `eval`, and no
`java.io.Serializable` involvement, so Java deserialization gadget chains do not
apply. A document naming `java.lang.Runtime` yields an inert `UnknownRecord`.
See [SECURITY.md](SECURITY.md).

[iana]: https://www.iana.org/assignments/cbor-tags/
[incognito]: https://github.com/replikativ/incognito
