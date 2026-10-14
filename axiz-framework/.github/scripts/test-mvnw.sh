#!/usr/bin/env bash
# Checks that the Maven wrapper never runs an unverified distribution: missing pinned checksum in
# strict mode, published checksum, pinned checksum and tampered download. Uses a local fake
# repository (file:// URLs), no network.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
REPO="$T/repo/org/apache/maven/apache-maven/9.9.9"
mkdir -p "$REPO" "$T/p/.mvn/wrapper" "$T/build/apache-maven-9.9.9/bin"
printf '#!/bin/sh\necho fake-mvn "$@"\n' > "$T/build/apache-maven-9.9.9/bin/mvn"
chmod +x "$T/build/apache-maven-9.9.9/bin/mvn"
(cd "$T/build" && zip -qr "$REPO/apache-maven-9.9.9-bin.zip" apache-maven-9.9.9)
(cd "$REPO" && sha512sum apache-maven-9.9.9-bin.zip | cut -d' ' -f1 > apache-maven-9.9.9-bin.zip.sha512)
cp "$ROOT/mvnw" "$T/p/mvnw"
PROPS="$T/p/.mvn/wrapper/maven-wrapper.properties"
echo "distributionUrl=file://$REPO/apache-maven-9.9.9-bin.zip" > "$PROPS"
run() { rm -rf "${T:?}/home"; cached "$@"; }
cached() { (cd "$T/p" && MAVEN_USER_HOME="$T/home" "$@" ./mvnw -q >/dev/null 2>&1); }
fail() { echo "FAIL: $*" >&2; exit 1; }

run env MVNW_REQUIRE_PINNED_CHECKSUM=true && fail "strict mode accepted an unpinned distribution"
run env || fail "published checksum not accepted"
(cd "$T/p" && ./mvnw --pin-checksum >/dev/null 2>&1) || fail "pin failed"
grep -q '^distributionSha512Sum=[0-9a-f]\{128\}$' "$PROPS" || fail "checksum not pinned"
run env MVNW_REQUIRE_PINNED_CHECKSUM=true || fail "pinned checksum not accepted"
sed -i 's/^distributionSha512Sum=./distributionSha512Sum=0/' "$PROPS"
run env && fail "tampered distribution accepted"
[ ! -e "$T/home/wrapper/dists/apache-maven-9.9.9/apache-maven-9.9.9" ] || fail "tampered zip unpacked"
# strict mode is not bypassed by a distribution already in the cache (second review, F10)
grep -v '^distributionSha512Sum=' "$PROPS" > "$T/props" && cp "$T/props" "$PROPS"
run env || fail "published checksum not accepted (cache warm-up)"
cached env MVNW_REQUIRE_PINNED_CHECKSUM=true && fail "strict mode ran a cached, unpinned distribution"
# a cache verified against another checksum is verified again (and a tampered download rejected)
(cd "$T/p" && ./mvnw --pin-checksum >/dev/null 2>&1) || fail "pin failed"
cached env MVNW_REQUIRE_PINNED_CHECKSUM=true || fail "cache verified against the same checksum rejected"
sed -i 's/^distributionSha512Sum=./distributionSha512Sum=0/' "$PROPS"
cached env && fail "cache reused although the pinned checksum changed"
# a distribution put in the cache without verification (no marker) is not trusted
(cd "$T/p" && ./mvnw --pin-checksum >/dev/null 2>&1) || fail "pin failed"
rm -rf "${T:?}/home" && mkdir -p "$T/home/wrapper/dists/apache-maven-9.9.9/apache-maven-9.9.9/bin"
printf '#!/bin/sh\necho UNVERIFIED\n' > "$T/home/wrapper/dists/apache-maven-9.9.9/apache-maven-9.9.9/bin/mvn"
chmod +x "$T/home/wrapper/dists/apache-maven-9.9.9/apache-maven-9.9.9/bin/mvn"
out=$(cd "$T/p" && MAVEN_USER_HOME="$T/home" ./mvnw 2>/dev/null) || fail "verified download failed"
[ "$out" != "UNVERIFIED" ] || fail "an unverified cached distribution was executed"
cmp -s "$ROOT/mvnw" "$ROOT/codegen/axiz-codegen-templates/src/main/resources/axiz/templates/shared/mvnw" \
  || fail "the generated projects' wrapper differs from the framework's"
echo "mvnw: all checks passed"
