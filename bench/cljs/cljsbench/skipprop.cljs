(ns cljsbench.skipprop
  "Property: `skip-from` must land exactly where `read!` lands.

  A skip that is one byte off produces an index pointing into the middle of an
  item, which reads back as a plausible WRONG VALUE rather than an error -- the
  failure class this project has spent seven audit rounds on. Run before
  trusting anything built on top of these primitives."
  (:require [boring.core :as b] [boring.reader :as rd]
            [goog.object :as gobj]))
(defn gen [d]
  (let [r (rand)]
    (cond (or (> d 3) (< r 0.30)) (rand-nth [1 -1 0 1000000 "x" "" :kw 'sym true false nil 1.5
                                             (js/Uint8Array.from #js [1 2 3])])
          (< r 0.55) (vec (repeatedly (rand-int 5) #(gen (inc d))))
          (< r 0.80) (into {} (map (fn [i] [i (gen (inc d))])) (range (rand-int 5)))
          :else (set (repeatedly (rand-int 4) #(rand-int 100))))))
(defn -main [& _]
  (let [bad (atom 0)]
    (dotimes [_ 20000]
      (let [v (gen 0) bs (b/encode v {:stringref false})
            r (rd/reader bs) skipped (rd/skip-from r 0)
            _ (rd/read! r) decoded (rd/position r)]
        (when (not= skipped decoded)
          (swap! bad inc)
          (when (< @bad 4) (println "MISMATCH" skipped decoded (pr-str v))))))
    ;; TWO independent failure signals, because the obvious one silently did
    ;; not work. `(set! (.-exitCode js/process) ...)` compiles, runs, and has
    ;; no effect under `-O advanced`: `process` is not in the externs this
    ;; build uses, so Closure renames the property and node never sees an
    ;; `exitCode`. Measured by inverting the property -- 20000 of 20000
    ;; disagreements reported, exit code 0.
    ;;
    ;; `goog.object/set` takes the name as a STRING, which Closure does not
    ;; rename, so this one survives. The `FAIL` prefix is the belt to that
    ;; brace: `bin/ci`'s `run` greps its logs for `^FAIL` because a zero exit
    ;; is not sufficient, and the old wording matched none of its patterns --
    ;; so this gate would have passed both ways at once.
    (if (zero? @bad)
      (println "skip agreed with decode on all 20000")
      (println "FAIL: skip disagreed with decode on" @bad "of 20000"))
    (gobj/set js/process "exitCode" (if (zero? @bad) 0 1))))
(set! *main-cli-fn* -main)
