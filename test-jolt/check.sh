#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
clojure -T:build javac
clojure -Sdeps '{:aliases {:oracle {:extra-paths ["test-jolt"]}}}' -M:oracle -m index-oracle "$out"
(cd test-jolt && "${JOLT:-jolt}" -m indexed-cases "$out")
clojure -Sdeps '{:aliases {:oracle {:extra-paths ["test-jolt"]}}}' -M:oracle -m record-cases write "$out"
(cd test-jolt && "${JOLT:-jolt}" -m record-cases read "$out")
