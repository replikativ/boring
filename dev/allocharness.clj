(ns allocharness
  "Is the allocation harness measuring the code, or itself?

  It reported 3688 B for a single two-byte read, which is impossible. Either
  the read allocates absurdly or `alloc` does. This asks the two cheapest
  questions there are: a function that returns a constant, and one raw interop
  call."
  (:import [com.sun.management ThreadMXBean]
           [org.replikativ.boring Reader]))

(def ^ThreadMXBean tmx (java.lang.management.ManagementFactory/getThreadMXBean))

(defn alloc [f n]
  (let [id (.getId (Thread/currentThread))]
    (dotimes [_ 300] (f))
    (let [a (.getThreadAllocatedBytes tmx id)]
      (dotimes [_ n] (f))
      (double (/ (- (.getThreadAllocatedBytes tmx id) a) n)))))

(defn -main [& _]
  (let [^bytes bs (byte-array 4096)
        ^Reader r (Reader. bs)]
    (println)
    (doseq [n [1000 10000 100000 1000000]]
      (println (format "  iterations %-9d  (fn [] 1) %8.2f B   .byteAt %8.2f B"
                       n (alloc (fn [] 1) n) (alloc #(.byteAt r 100) n))))
    (println)
    (println "  A constant-returning fn cannot allocate per call. Anything")
    (println "  above ~0 here is the MEASUREMENT, amortised over too few")
    (println "  iterations to swamp its own fixed cost.")
    (shutdown-agents)))
