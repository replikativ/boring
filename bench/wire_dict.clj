(ns wire-dict
  "Where does the dictionary win actually come from?

  Earlier measurement: 500 small wire messages compressed INDEPENDENTLY save
  5-17% under any compressor, while a trained zstd dictionary saves 78%. That
  gap is the whole argument for putting a dictionary on the kabel wire.

  But 'trained dictionary' and 'shared history' are different mechanisms with
  very different costs:

    trained    someone must train it, ship it, version it, and both peers must
               agree on WHICH one -- a mismatch is silent corruption, so it
               needs negotiation.
    streaming  one compression context per connection; message N is compressed
               against messages 1..N-1. Nothing to train, ship, or negotiate --
               but it is stateful and order-dependent, so it needs an ordered
               reliable transport (a websocket is one) and it desynchronises
               permanently if a message is lost.

  If streaming alone approaches the trained number, the design collapses to
  'one context per connection' and the entire negotiation problem disappears.
  That is the question here."
  (:require [boring.core :as boring])
  (:import [com.github.luben.zstd Zstd ZstdCompressCtx ZstdDictCompress
            ZstdDictTrainer ZstdOutputStream]
           [java.io ByteArrayOutputStream]
           [java.util.zip Deflater]))

(defn- messages [n]
  (vec (for [i (range n)]
         {:type :sync/publish
          :topic :datahike/store
          :sender #uuid "aaaaaaaa-0000-0000-0000-000000000001"
          :key (keyword (str "node-" i))
          :value {:e (+ 100 i) :a :person/name :v (str "person-" i)
                  :tx (+ 536870912 i) :added true}})))

(defn- independent
  "Each message compressed on its own -- what a stateless middleware does."
  [encoded level]
  (reduce + (map #(alength (Zstd/compress ^bytes % (int level))) encoded)))

(defn- streaming
  "ONE context for the whole connection, flushed after each message so the peer
  can decode incrementally. Cumulative output is what actually crosses the wire."
  [encoded level]
  (let [baos (ByteArrayOutputStream.)
        out (ZstdOutputStream. baos (int level))]
    ;; setCloseFrameOnFlush false keeps a single frame across the connection,
    ;; which is what preserves the history; true would start a new frame per
    ;; message and throw the shared context away -- i.e. silently degrade to
    ;; the independent case.
    (.setCloseFrameOnFlush out false)
    (doseq [^bytes b encoded] (.write out b) (.flush out))
    (.close out)
    (alength (.toByteArray baos))))

(defn- deflate-streaming
  "One Deflater for the whole connection, SYNC_FLUSH between messages.

  This is precisely what a websocket's permessage-deflate extension does with
  context takeover (RFC 7692) -- which browsers implement natively. If this is
  competitive with zstd streaming, the right answer for the browser leg is to
  enable a websocket extension rather than ship a compressor."
  [encoded]
  (let [d (Deflater. Deflater/BEST_COMPRESSION true)
        out (ByteArrayOutputStream.)
        buf (byte-array 65536)]
    (doseq [^bytes b encoded]
      (.setInput d b)
      (loop []
        (let [n (.deflate d buf 0 (alength buf) Deflater/SYNC_FLUSH)]
          (when (pos? n) (.write out buf 0 n) (when (= n (alength buf)) (recur))))))
    (.end d)
    (.size out)))

(defn- trained-dict ^bytes [encoded k size]
  (let [t (ZstdDictTrainer. (* 1024 1024) size)]
    (doseq [^bytes b (take k encoded)] (.addSample t b))
    (.trainSamples t)))

(defn- with-dict [encoded ^bytes dict level]
  (let [d (ZstdDictCompress. dict (int level))]
    (with-open [ctx (ZstdCompressCtx.)]
      (.loadDict ctx d)
      (reduce + (map (fn [^bytes b] (alength (.compress ctx b))) encoded)))))

(defn -main [& _]
  (let [msgs (messages 500)
        enc (mapv #(boring/encode %) msgs)
        raw (reduce + (map alength enc))
        dict (trained-dict enc 200 (* 16 1024))
        pct (fn [x] (format "%+6.1f%%" (* 100.0 (/ (- x raw) (double raw)))))]
    (println)
    (println "500 kabel-sized messages, boring-encoded. Total bytes on the wire:")
    (println)
    (printf "%-42s %9s %8s%n" "strategy" "bytes" "vs raw")
    (println (apply str (repeat 62 \-)))
    (printf "%-42s %9d %8s%n" "uncompressed" raw "  --")
    (doseq [lvl [3 19]]
      (printf "%-42s %9d %8s%n" (str "independent per message, zstd-" lvl)
              (independent enc lvl) (pct (independent enc lvl))))
    (doseq [lvl [3 19]]
      (printf "%-42s %9d %8s%n" (str "STREAMING, one context, zstd-" lvl)
              (streaming enc lvl) (pct (streaming enc lvl))))
    (printf "%-42s %9d %8s%n" "trained dictionary, zstd-3"
            (with-dict enc dict 3) (pct (with-dict enc dict 3)))
    (printf "%-42s %9d %8s%n" "STREAMING deflate (= permessage-deflate)"
            (deflate-streaming enc) (pct (deflate-streaming enc)))
    (println)
    (println "How fast does streaming converge? Cumulative bytes after N messages,")
    (println "so the cost of a SHORT connection is visible rather than assumed:")
    (doseq [n [1 5 10 25 50 100 500]]
      (let [sub (vec (take n enc))
            s (streaming sub 3)
            r (reduce + (map alength sub))]
        (printf "  after %3d msgs: %7d B streamed vs %7d raw   %s   (%.1f B/msg)%n"
                n s r (format "%+6.1f%%" (* 100.0 (/ (- s r) (double r))))
                (/ (double s) n))))
    (println))
  (shutdown-agents))
