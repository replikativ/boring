# Review findings — `lazy-index`, before merge

Three independent reviews of this branch: API/redundancy, memory safety and
correctness, and an evaluation of routing descent validation through the Reader.
Every row below was **confirmed by running it**, not by reading.

This file is the merge checklist. A finding leaves it only when a test pins the
fix. Status: `[ ]` open, `[x]` fixed, `[-]` deliberately not doing (with reason).

---

## 0. The invariant was wrong as written

`boring.nav`'s promise — navigating answers exactly as realising does — is
**already false on `main`**, for plain arrays and maps, with no tag involved:

```
{:max-items 3}, (vec (range 10))
  nav count => 10, nav (get c 5) => 5     decode => :boring/max-items-exceeded
{:max-depth 2}, {:a {:b {:c 1}}}
  nav (get-in c [:a :b :c]) => 1          decode => :boring/max-depth-exceeded
```

And it should be false: charging a document-wide item budget for a lookup that
reads three items would defeat the namespace. So the goal is not "nav accepts
what the reader accepts". It is:

> For a document the configured reader would decode successfully, every nav
> answer equals the realised answer. Where the reader would raise on the subtree
> a nav operation actually touches, nav raises the same typed error. Nav does
> not charge document-wide resource budgets for a partial read.

That last clause is a deliberate, documented exception. Writing it down is what
stops it being refiled as a defect.

- [ ] **I0** Restate the invariant in the `boring.nav` ns docstring.

## 0b. Why every one of these shipped

Each nav property — the defspecs in `generative_test.cljc:407-500`, and
`nav-conformance/check-value` itself — feeds nav **bytes boring wrote, under
default options**. `check-value` inherits the blind spot because it encodes `v`
itself. The divergences live entirely in the complement: bytes boring did not
write, and options other than the default. Not one row below is reachable from
the existing generators.

- [x] **G1** DONE -- `test/boring/nav_divergence_test.clj`. Cross `test/boring/option_matrix.cljc` x `test/boring/hostile.cljc`
      x `check-value`: every option combination x mutated-byte document, assert
      nav agrees with realise **or both raise**. Ships with the open rows below
      listed as known divergences, so the suite is green and each fix removes an
      entry. A ratchet, not a wish list.

      Four properties, because the obvious one is false (see section 0):
      P1 no untyped throwable, ever, on any input; P2 `value` agrees with
      `decode`; P3 when `decode` SUCCEEDS every other operation agrees; P4
      `count` never exceeds the remaining bytes. P1 and P4 are absolute; P2/P3
      are conditioned on the reader succeeding, which is what makes room for the
      budget exception without weakening them.

      It independently reproduced **S1**, **S2** and the `:on-unknown-record fn`
      half of **S6** -- 100 failures on first run -- and all three are now
      ratcheted.

      WHAT IT CANNOT REACH, recorded so the gap is not mistaken for coverage:
      the rest of **S6** and all of **S7** -- descending into a tag the reader
      would REFUSE -- violate none of P1-P4. Both `decode` and `nav/value` raise,
      so P2 agrees, and P3 does not run. The divergence is that `(get c :a)`
      answers. That cannot be made generic without forbidding a shallow lookup
      on a document with one deep malformed element, which is what lazy
      navigation is for. It is a per-descent obligation and belongs in PR 3.

---

## 1. Untyped exceptions on undamaged data

The contract is that only `ex-info` with a `:boring/...` type escapes.

- [x] **S1** `typed-array-tags` (`nav.clj:896-910`) accumulates unsigned then
      applies **checked** casts, so every negative element of a `short[]`,
      `int[]` or `float[]` throws. `(short-array [-1])` -> `IllegalArgumentException`;
      `(int-array [-1])` and `(float-array [-1.0])` -> `ArithmeticException`;
      `decode` returns the value fine. Tag 85 fails for *every* negative float.
      FIXED: `unchecked-short` / `unchecked-int`. Verified against `realize` for
      value AND class at the boundaries (Short/MIN, Integer/MIN, -0.0).
      Why missed: fixtures built from `(range n)` — the sign-free half.
- [x] **S2** `(get cursor 2147483648)` -> `ArithmeticException` (`nav.clj:613`,
      from `816e64d`). `(get [1 2 3] 3e9)` on the realised vector is total.
      FIXED: `(long k)`; `nth-item` already range-checks. Verified that nav now
      agrees with `clojure.core/get` on the realised value for 0, -1, 2^31,
      3e9, 0.0, 1.0 and a keyword, across vector / long[] / shaped rows.
- [x] **S3** `Cursor.count` on a tag (`nav.clj:729`) skips the `room` guard the
      array/map branch applies, reproducing verbatim the bug its own neighbouring
      comment describes. A tag-27 frame around a map declaring 2^20 pairs over
      two bytes: `count` -> 1048576, `decode` -> `:boring/bad-count`. Above 2^31
      the `(int ...)` throws. Reachable for record, typed and shaped views.
      FIXED: the same `room` check, with `(quot room 2)` for map-kinded views
      because the multiplication is what overflows on the counts worth
      rejecting. All four crafted documents now report `:boring/bad-count` from
      both nav and decode. This also closes the `count` half of **S8**; the
      `nth` half is still open.
- [x] **S4** Under `:trust-index :trusted`, 6 of 222 single-byte footer mutants
      escape untyped, e.g. `ClassCastException: TaggedValue cannot be cast to [J`.
      `index-payload`'s own comment says the O(1) length check stays
      unconditional to prevent exactly this class; the sibling O(1) check — that
      each `slots` element is one of the four expected primitive array types — is
      made nowhere, and `expand-slot` (`nav.clj:1226`) blindly hints `^longs`.
      Under the default path `node-sound?` forces `slot-at` inside a `try`;
      `:trusted` skips that, and `lookup-map`/`nth-item` then call it unguarded.
      FIXED: the slot element-type check joins the unconditional block. O(nodes)
      rather than O(1), but one `instance?` per node against a whole-container
      walk. Re-swept: **0 untyped in both trust modes**.
- [x] **S5** Minor: `(get typed-cursor 0.0)` -> nil, `(get (realize c) 0.0)` -> the
      element. `RT.getFrom` on a Java array tests `instanceof Number`, not
      `Util.isInteger`, so the `(integer? k)` guard is stricter than the thing it
      mirrors. Vectors are unaffected.
      FIXED: the key predicate moved onto the VIEW (`:key-pred`), because the two
      vector-kinded descents realise to different host types — a shaped array to
      a Clojure vector (`integer?`), a typed array to a Java array (`number?`).
      Hard-coding one guard for both was the bug.

## 2. Silently wrong values on undamaged data

- [x] **S6** `record-view`'s gate (`nav.clj:1000`) is `recordCtor == nil`, but
      that has **four** outcomes in the Reader (`Reader.java:2598-2823`), driven
      by options the gate never consults:
      - `:on-unknown-record <fn>` -> nav `get`/`count`/`seq` all wrong
      - `:on-unknown-record :error` -> nav answers where the reader refuses,
        bypassing a policy gate
      - `:auto-construct-records? true`, payload missing a basis field -> nav
        count 1 / `get :b` absent; decode count 2 / `get :b` nil
      - same, wire key order != basis order -> `seq` order differs
      The docstring's "the two cannot drift" is true of the registry and false of
      the decision.
      FIXED via option D: `Reader.recordDescendable` sits beside the tag-27
      dispatch and reads the same fields. Nav mirrors nothing. Verified across
      all six configurations: descent happens only for unregistered+`:fallback`
      and registered+declared, and the ANSWERS agree with `decode` in every case
      — nav either matches or declines.
- [x] **S7** **Eight** reserved tag-27 names with a map payload descend and
      answer, while `decode` raises `:boring/bad-tag-content` for all of them:
      `clojure/sorted-set`, `clojure/queue`, `clojure/with-meta`, `clojure/char`,
      `clojure/ex-info`, `java/throwable`, `java/*-array`, `java/period`. The
      `sorted?` special case covers only `clojure/sorted-map`. `sorted-set` is
      precisely the "OPERATION-CHANGING, `get` on a set is MEMBERSHIP, stays
      opaque" case the dispatch comment claims is excluded.
      FIXED: all twelve are refused by `Reader.isReservedRecordName`, listed once
      beside the switch it mirrors and pinned by a test that names all twelve.

      COST, recorded honestly: `clojure/sorted-map` goes with them, giving up a
      measured 73x. Descent there was sound only for sorted-maps BORING wrote —
      Clojure cannot build one with mutually incomparable keys and the writer
      refuses a custom comparator, but a hand-crafted document can claim the name
      over any keys, and then `decode` raises while `count` and `seq` answer.
      Proving comparability means realising every key at view-build: O(K) on the
      operation the descent made O(log K). Revisitable via the shaped-row
      watermark pattern if anyone stores sorted-maps hot.
- [ ] **S8** `typed-view` (`nav.clj:928`) never bounds the payload against the
      source. Tag 79 declaring 1 MiB with 8 bytes present: `count` -> 131072,
      `nth 0` fabricates an element; `decode` -> `:boring/bad-count`.
      `readTypedArray` checks this via `checkCount` (`Reader.java:2073`).
- [ ] **S9** `shaped-view` (`nav.clj:938`) does not enforce **row length == key
      count** (`Reader.java:2554`), does not reject **duplicate shape keys**
      (`checkShapeKeys`, `Reader.java:1785`), and does not require **n >= 1**.
      A 3-value row against 2 keys navigates; a duplicate-key shape gives
      `count` 2 with `(count (value c))` 1 on one cursor.
      Row-length checking must be **incremental** — a validated-up-to watermark
      in the cached view. Eagerly it takes shaped `count` from 644 ns to 10.4 us
      (16x) on well-formed data, while on any path that already walks rows
      (`seq`, `reduce`, `nth i`) it is free: 0.68 ns/row.

## 3. Leaks and unbounded growth

- [ ] **S10** The tag-view cache (`nav.clj:1051-1070`) grows one entry per tag
      **offset touched**, `::none` included. Measured: 2000 entries after a
      `reduce` over `nav/items` of 2000 records; 2000 useless entries for 2000
      *sets*. Contradicts `items`' own promise (`nav.clj:1826`) that "nothing
      before the cursor you are holding stays live", on exactly the workload the
      branch was built for. Its stated motivation — a caller walking rows — is
      satisfied by a single-slot `volatile!` of `[off view]`, since all rows of
      one shaped array share one tag offset.
- [ ] **S11** The probe cache on a `NavContext` is shared across every source and
      never bounded: 5000 entries after 5000 distinct keys. With computed keys
      that is a leak for the life of the scan. Bound it, or say so in the
      docstring.

## 4. Concurrency — clean

No defects. 200 threads on a shared context: 0 mismatches. `fork` across 16
threads: correct. The deliberately-raced verdict arrays fail conservatively in
both write orderings. `slot-at` uses `AtomicReferenceArray` + CAS correctly.

- [ ] **S12** Minor: without `fork`, 1 of 16 threads produced a raw
      `NullPointerException` alongside 13 `:boring/concurrent-use`. Documented-
      unsafe territory, but the detector is described as naming the overlap.

## 5. API, redundancy, consolidation

- [ ] **A1** The ns docstring still says "TAGS ARE OPAQUE ... the slow path IS the
      reference implementation", which this branch's central feature contradicts.
      The `WHAT IS IMPLEMENTED` table omits descents, `context`, and
      `nav-conformance`. (Do together with **I0**.)
- [ ] **A2** `zipper` never learned about descents (`nav.clj:1913`): `branch?`
      tests `value-type`, which returns `:tag`, so `zip/down` is nil where
      `count`/`seq`/`get`/`nth`/`reduce` all descend. Fix: a shared `container?`
      predicate; add a zipper leg to `check-value`, whose absence is why this
      survived.
- [ ] **A3** The two reader-config paths. `core.clj:612` claims
      `configure-reader!` "goes through here" — it does not; they are independent
      implementations. The justification I wrote (an allocation on the decode
      path) measures **0.5 ns** against a 140 ns decode of the smallest realistic
      document. Delegate. Consider a `deftype ReaderConfig` with named primitive
      fields so the pairing is compiler-checked rather than eleven positional
      `aset`/`aget`.
- [ ] **A4** `apply-reader-config!` (`core.clj:632`) is the only new function with
      no docstring, and its body is eleven magic indices that must match
      `reader-config`'s eleven positionally.
- [ ] **A5** Dead: `:shape` in the view map (`nav.clj:970`) is never read.
- [ ] **A6** `::realise` is honoured in `valAt`'s `:map` branch but not `:vector`
      (`nav.clj:663-674, 703-708`), so a future builder returning it from `:nth`
      leaks `:boring.nav/realise` to the user. Handle it, or state that it is
      `:map`-only.
- [ ] **A7** "This is the extension point" (`nav.clj:1043`) overstates: the table
      is `^:private`, and a user could not construct a `Cursor` to return anyway.
- [ ] **A8** `source` and `items` accept a `NavContext` in the opts position but
      only `context`'s docstring says so; a wrong value fails absurdly
      (`:boring/read-only ... assoc is not supported`). Guard and document.
- [ ] **A9** `reduce` over a navigable tag routes through `seq`, and `seq` for
      `:vector` is `(seq (vec ((:items v))))` — a materialised vector, against the
      ns docstring's "no intermediate seq". For a 100k typed array that boxes
      100k numbers. Cheapest honest fix: drop the `vec`.
- [ ] **A10** `fork` drops the context's pre-resolved config (`nav.clj:263`).
- [ ] **A11** `fork-nav`'s fresh view cache is load-bearing — cached views close
      over the parent's Reader, so sharing would share the Reader, the one thing
      `fork` exists to prevent — and is undocumented.
- [ ] **A12** `shapes` field name and comment are stale; it caches all three kinds
      plus negatives. Rename to `views`.
- [ ] **A13** `Cursor.count` is misindented and calls `(major nav off)` twice.
- [ ] **A14** Stale `declare` (`nav.clj:70`): only `->Cursor`, `cursor-at`,
      `read-index`, `read-index*`, `tag-view` need forward declaration.
- [ ] **A15** `shaped-view` and `record-view` duplicate a "tag wraps a definite
      2-element array" prologue; all three builders re-test the tag they were
      dispatched on, which is now dead.
- [ ] **A16** `shaped_nav_test` and `typed_nav_test` hand-roll their comparisons
      instead of calling `check-value`.

## 6. Open questions and deferred

- [ ] **Q1** Audit `frame.cljc` and the index path for the same "what we emit vs
      what we accept" gap. It held three-for-three in `nav.clj`.
- [ ] **Q2** Suspicion: `child-offsets` (`nav.clj:527`) compares the declared
      count against `room` **before** doubling for maps, while `Cursor.count`
      compares `2n`. Cosmetic (both typed) but the guards should agree.
- [ ] **Q3** Suspicion: a `:sorted` index node over a `clojure/sorted-map`
      payload is ordered by Clojure `compare`, not canonical CBOR byte order, so
      the binary search runs over an array not sorted by its comparison function.
      Believed safe only because `confirm` re-derives every negative by an honest
      scan. Needs an explicit comment, or deleting `confirm` as "redundant" later
      silently breaks sorted-map lookups.
- [ ] **Q4** Perf, optional: the `typed-array-tags` Clojure map lookup costs
      20.6 ns — 3x the entire validation and a third of the view build. The
      target if descent build time ever matters.
- [-] **Q5** A public tag extension point. Not now: `:nth` may return a cursor, a
      realised value, or a private sentinel, and users cannot construct a Cursor.
- [-] **Q6** Split `boring.nav.index` into its own namespace (~550 lines,
      mechanical). Only if `nav.clj` keeps growing.
- [x] **Q7** DECIDED: do not generalise. The symmetry with `boring/writer` is
      superficial — a Writer exists because it OWNS A BUFFER, a real resource;
      resolving options is incidental to it. A context owns CACHES. Generalising
      across them would add a third concept justified by neither.

      The performance case does not survive measurement either: option
      resolution is 11.6 ns against a 140 ns decode of the smallest realistic
      document, and ~1.6 us for a 229-byte blob. That is 0.7%, invisible. It
      mattered in nav only because `nav/source` is 29 ns in total and does
      almost nothing else.

      Also rejected: making the cache invisible, with `source` looking up a
      resolved context behind the scenes. Keying on opts-map equality means
      hashing the map per source, which costs more than the resolution it saves,
      and adds hidden global state.

      So: `boring.core` unchanged, `decode` keeps taking a plain map,
      `nav/context` stays public in `nav` under that name, and the public
      surface stays two functions. The polymorphic opts slot is a real defect
      and is tracked separately as **A8**.
