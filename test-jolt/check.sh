#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
if [ -z "${JOLT:-}" ]; then
  archive=jolt-v0.8.30-x86_64-linux.tar.gz
  (cd "$out"
   curl -fsSL --retry 3 -O "https://github.com/jolt-lang/jolt/releases/download/v0.8.30/$archive"
   echo "c9eb00ce9b58e6ab19dcfc324edad2ad5127bab7339e8d018ce2c30876037bdd  $archive" | sha256sum -c -
   tar -xzf "$archive")
  JOLT="$out/${archive%.tar.gz}/jolt"
fi

version=0.0.0-jolt-test
BORING_VERSION="$version" clojure -T:build jar
jar="boring-$version.jar"
artifact="$out/maven/org/replikativ/boring/$version"
mkdir -p "$artifact" "$out/consumer" "$out/gold"
cp "target/release/$jar" "$artifact/"
unzip -p "$artifact/$jar" META-INF/maven/org.replikativ/boring/pom.xml > "$artifact/boring-$version.pom"
cp test-jolt/indexed_cases.clj test-jolt/record_cases.clj "$out/consumer/"
cat > "$out/consumer/deps.edn" <<EDN
{:paths ["."] :mvn/local-repo "$out/maven"
 :deps {org.replikativ/boring {:mvn/version "$version"}}}
EDN

oracle='{:aliases {:oracle {:extra-paths ["test-jolt"]}}}'
clojure -Sdeps "$oracle" -M:oracle -m index-oracle "$out/gold"
clojure -Sdeps "$oracle" -M:oracle -m record-cases write "$out/gold"
cd "$out/consumer"
"$JOLT" -m indexed-cases "$out/gold"
"$JOLT" -m record-cases read "$out/gold"
