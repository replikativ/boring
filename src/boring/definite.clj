(ns ^:no-doc boring.definite)

(defn end-at
  "End of a definite-length CBOR item, or -1 for malformed or unsupported heads."
  [bs start]
  (let [size (alength bs)
        u #(bit-and 255 (aget bs %))]
    (loop [p start, items 1]
      (cond (zero? items) p
            (or (neg? p) (>= p size)) -1
            :else
            (let [b (u p) major (quot b 32) ai (mod b 32)
                  width (case ai 24 1 25 2 26 4 27 8 0)
                  end (+ p 1 width)]
              (if (or (>= ai 28) (> end size)) -1
                  (let [n (cond (#{0 1 6 7} major) 0
                                (zero? width) ai
                                :else (loop [q (inc p), acc 0]
                                        (if (or (= q end) (> acc size)) acc
                                            (recur (inc q) (+ (* acc 256) (u q))))))
                        next-p (+ end (if (#{2 3} major) n 0))
                        todo (+ (dec items) (case major 4 n 5 (* 2 n) 6 1 0))]
                    (if (> todo (- size next-p)) -1 (recur next-p todo)))))))))
