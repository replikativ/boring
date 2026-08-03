(ns nav
  "Does `boring.nav` deliver on the prototype numbers?

  The prototype that motivated this layer was a hand-written scanner measuring
  6.6x on a path lookup and ~1000x on a blob. Those were an upper bound: no
  registry, no tag handling, no guard rails, no cursor allocation. This runs
  the SHIPPED api, so the numbers are the ones a caller actually gets.

  Three questions:
    1. path lookup vs decode-then-get-in, on the heap
    2. the same over an mmap'ed file
    3. handing back a blob's bytes vs materialising it

  Run: clojure -M:bench -m nav"
  (:require [boring.core :as boring]
            [boring.nav :as nav]
            [boring.mmap :as mmap])
  (:import (java.io File FileOutputStream)
           (org.replikativ.boring Reader)))

(set! *warn-on-reflection* true)

(def opts {:stringref false})

(defn ab
  "Alternating bursts, min over rounds. µs/op."
  [fa fb ^long iters ^long rounds]
  (dotimes [_ (* 2 iters)] (fa) (fb))
  (loop [r 0 ba Long/MAX_VALUE bb Long/MAX_VALUE]
    (if (= r rounds)
      [(/ ba (double iters) 1000.0) (/ bb (double iters) 1000.0)]
      (let [t0 (System/nanoTime) _ (dotimes [_ iters] (fa))
            t1 (System/nanoTime) _ (dotimes [_ iters] (fb))
            t2 (System/nanoTime)]
        (recur (inc r) (min ba (- t1 t0)) (min bb (- t2 t1)))))))

(defn- spit-bytes ^File [^bytes bs]
  (let [f (doto (File/createTempFile "boring-nav" ".cbor") .deleteOnExit)]
    (with-open [o (FileOutputStream. f)] (.write o bs))
    f))

(def customers
  (into {} (for [i (range 200)]
             [(str "customer-" i)
              {"name"    (str "Person Number " i)
               "email"   (str "person" i "@example.com")
               "address" (str i " Some Long Street Name, Some City, 12345")
               "notes"   (apply str (repeat 6 "padding text to make values bigger "))
               "id"      (+ 1000000 i)
               "active"  true}])))

(defn -main [& _]
  (let [^bytes bs (boring/encode customers opts)
        c (nav/source bs opts)
        rdr (Reader. bs)
        path ["customer-137" "name"]]

    (println (format "\nstructure: %.1f KB, 200 customers x 6 fields\n"
                     (/ (alength bs) 1024.0)))
    (println (format "%-44s %10s %10s %8s" "" "nav" "decode" "ratio"))

    (let [[a b] (ab #(nav/value (get-in c path))
                    #(get-in (do (.reset rdr bs) (.read rdr)) path)
                    200 30)]
      (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                       "get-in one leaf (heap)" a b (/ b a))))

    ;; count is read from the head, so it should not depend on size at all
    (let [[a b] (ab #(count c)
                    #(count (do (.reset rdr bs) (.read rdr)))
                    200 30)]
      (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                       "count the top-level map" a b (/ b a))))

    ;; Scanning every customer's :name -- the reducers case. Still beats a full
    ;; decode because the other five fields per record are never materialised.
    (let [[a b] (ab #(reduce (fn [acc e] (+ acc (count (nav/value (get (val e) "name")))))
                             0 c)
                    #(reduce-kv (fn [acc _ v] (+ acc (count (get v "name")))) 0
                                (do (.reset rdr bs) (.read rdr)))
                    50 20)]
      (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                       "reduce over all 200, one field each" a b (/ b a))))

    ;; mmap: same lookup, off-heap
    (let [f (spit-bytes bs)
          [mc arena] (mmap/mmap-source f opts)
          ^java.lang.foreign.Arena arena arena]
      (let [[a b] (ab #(nav/value (get-in mc path))
                      #(get-in (do (.reset rdr bs) (.read rdr)) path)
                      200 30)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                         "get-in one leaf (mmap'ed file)" a b (/ b a))))
      (.close arena))

    ;; blobs: the case where decoding IS the copy
    (let [payload (byte-array (* 1024 1024))
          blob-doc {"meta" {"id" 7} "data" payload}
          ^bytes bbs (boring/encode blob-doc opts)
          bc (nav/source bbs opts)
          brdr (Reader. bbs)]
      (println (format "\nblob: one 1 MiB bytestring beside a small map\n"))
      (println (format "%-44s %10s %10s %8s" "" "nav" "decode" "ratio"))
      (let [[a b] (ab #(nav/byte-span (get bc "data"))
                      #(count ^bytes (get (do (.reset brdr bbs) (.read brdr)) "data"))
                      50 20)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.0fx"
                         "locate the blob vs materialise it" a b (/ b a))))
      (let [[a b] (ab #(nav/value (get-in bc ["meta" "id"]))
                      #(get-in (do (.reset brdr bbs) (.read brdr)) ["meta" "id"])
                      50 20)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.0fx"
                         "read a field PAST the blob" a b (/ b a)))))

    ;; The log shape: a CBOR sequence of events, scanned for the few that match.
    ;; This is the case a logging backend actually has, and the one where
    ;; `items` earns its keep -- a filter on one field never builds the other
    ;; four, and `reduced` stops the walk where a decode-everything pass cannot.
    (let [events (vec (for [i (range 5000)]
                        {"lvl" (if (zero? (mod i 500)) "error" "info")
                         "n" i
                         "msg" (str "event number " i)
                         "ctx" {"thread" "main" "ns" "app.core" "line" i}}))
          baos (java.io.ByteArrayOutputStream.)
          w (boring/writer 8192)
          _ (doseq [e events] (boring/write-to! w e baos opts))
          ^bytes log-bs (.toByteArray baos)
          xf (comp (filter #(= "error" (nav/value (get % "lvl"))))
                   (map #(nav/value (get % "n"))))]
      (println (format "\nlog: %d events, %.1f KB as a CBOR sequence\n"
                       (count events) (/ (alength log-bs) 1024.0)))
      (println (format "%-44s %10s %10s %8s" "" "nav" "decode" "ratio"))
      (let [[a b] (ab #(into [] xf (nav/items log-bs opts))
                      #(into [] (comp (filter (fn [e] (= "error" (get e "lvl"))))
                                      (map (fn [e] (get e "n"))))
                             (boring/decode-seq log-bs opts))
                      5 20)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                         "scan a log for matching events" a b (/ b a))))
      (let [[a b] (ab #(reduce (fn [_ c] (reduced (nav/value (get c "n")))) nil
                               (nav/items log-bs opts))
                      #(reduce (fn [_ e] (reduced (get e "n"))) nil
                               (boring/decode-seq log-bs opts))
                      50 20)]
        (println (format "%-44s %9.2fµs %9.2fµs %7.1fx"
                         "first event only (early exit)" a b (/ b a)))))

    (println "\nThe blob rows are the shape that matters most: skipping a bytestring")
    (println "is a jump, not a walk, so the cost of ignoring one does not scale")
    (println "with its size. Everything above is a byte[] source except where")
    (println "noted; off-heap decode costs ~1.35x, which is why the mmap row")
    (println "trails the heap row.")
    (println)
    (println "WHERE NAV LOSES, and it is worth knowing: the early-exit row is")
    (println "SLOWER than decode-seq. decode-seq is lazy, so stopping at the")
    (println "first item already decodes only that item -- and for one small")
    (println "item, a cursor plus a key probe costs more than just decoding it.")
    (println "nav wins by what it SKIPS and what it does not materialise. When")
    (println "you are going to touch nearly everything in a small item anyway,")
    (println "decode is the cheaper call.")))
