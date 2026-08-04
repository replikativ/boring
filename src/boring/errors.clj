(ns ^:no-doc boring.errors
  "The one decode error boundary, in one place.

  Not part of the public API -- it lives in its own namespace only because
  `boring.core` and `boring.nav` both need it, and a private macro cannot cross
  a namespace. `boring.core` is the API namespace; nothing internal should have
  to become public to be shared.

  It exists because the boundary WAS duplicated. `with-decode-errors` sat
  private in `boring.core` and was applied to four of six JVM read paths; the
  two it missed grew weaker substitutes instead -- `decode-seq-from`
  hand-rolled the `IndexOutOfBoundsException` half with a comment claiming it
  applied \"the same conversion `with-decode-errors` applies at every other
  entry point\", and `boring.nav` had nothing, so `nav/value` raised a bare
  `java.lang.StackOverflowError` on input an attacker chooses. doc/SECURITY.md
  promises that cannot escape.")

(defmacro with-decode-errors
  "Convert the two throwables a decode can raise from outside its own type
  system into typed `ex-info`s.

  Reading past the end of the buffer surfaces as an ArrayIndexOutOfBounds from
  deep in the decode loop — the exact thing datahike's dump requirements ask us
  not to do. Converting at the boundary keeps the hot path free of per-read
  bounds checks while still giving callers a typed error.

  Wrap the byte-reading call and nothing else. Wrapped around a caller's
  reduce, the `StackOverflowError` branch would relabel the CALLER's own
  recursion as a document depth error; the original is attached as the cause
  either way, so the two remain distinguishable, but the narrower the body the
  less that matters."
  [& body]
  `(try
     ~@body
     (catch IndexOutOfBoundsException e#
       (throw (ex-info "boring: input ended mid-value (truncated or malformed)"
                       {:type :boring/truncated-input} e#)))
     ;; A STACK OVERFLOW IS A DEPTH ERROR, and it has to be caught to be one.
     ;;
     ;; `:max-depth` bounds recursion in ITEMS, and the stack it costs per item
     ;; is not uniform: a chain of tags costs about 2.5x a chain of containers,
     ;; so 440 nested tags -- 881 bytes -- overflow a 1 MiB thread stack while
     ;; the default limit of 1024 is still nowhere in sight. Capping the option
     ;; does not fix that, because the cap was calibrated on the main thread's
     ;; 8 MiB stack and a servlet or agent thread gets a fraction of it: CI
     ;; passes and the request thread does not.
     ;;
     ;; Catching Error is normally wrong. Here the boundary is a decoder entry
     ;; point, the stack has fully unwound by the time this runs, and the
     ;; alternative is an untyped failure that doc/SECURITY.md promises cannot
     ;; escape -- on input an attacker chooses, on whatever stack the caller
     ;; happens to have.
     ;;
     ;; The original is the cause, as it is on the branch above. It used to be
     ;; dropped, which left a caller unable to tell a document that nests too
     ;; deep from their own recursion overflowing inside a `nav` traversal.
     (catch StackOverflowError e#
       (throw (ex-info (str "boring: input nests deeper than this thread's stack can "
                            "decode; lower :max-depth or decode on a thread with a "
                            "larger stack")
                       {:type :boring/max-depth-exceeded} e#)))))
