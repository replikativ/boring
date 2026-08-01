(ns prof
  "Profile the keyword-decode hot loop. Two hypotheses were guessed at before
  this: the duplicate-key check (right, 27% on int-maps) and read() inlining
  (wrong, no effect). Measure instead."
  (:require [boring.core :as boring]
            [clj-async-profiler.core :as p])
  (:import (org.replikativ.boring Reader)))

(def kw-flat (vec (take 1000 (cycle [:e :a :v :tx :added]))))

(defn -main [& _]
  (let [bs (boring/encode kw-flat)
        rdr (Reader. (byte-array 1))]
    (dotimes [_ 20000] (boring/decode-with rdr bs))     ; warm
    (println "profiling...")
    (p/start {:event :itimer :interval 200000})
    (dotimes [_ 400000] (boring/decode-with rdr bs))
    (let [f (p/stop {:format :collapsed})]
      (println "collapsed stacks ->" (str f)))))
