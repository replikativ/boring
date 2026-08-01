(ns cljsbench.runner
  "Runs the SAME conformance suite as the JVM — that is the point of keeping it
  in .cljc. Anything the JVM asserts, CLJS must assert too."
  (:require [cljs.test :as t]
            [boring.conformance-test]
            [boring.generative-test]
            [boring.golden-test]))

(enable-console-print!)

(defmethod t/report [::t/default :end-run-tests] [m]
  (println (str "\n" (:test m) " tests, " (+ (:pass m) (:fail m) (:error m))
                " assertions, " (:fail m) " failures, " (:error m) " errors"))
  (set! (.-exitCode js/process) (if (t/successful? m) 0 1)))

(defn -main [& _]
  (t/run-tests 'boring.conformance-test 'boring.generative-test 'boring.golden-test))

(set! *main-cli-fn* -main)
