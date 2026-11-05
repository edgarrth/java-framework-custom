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
cat > "$T/build/apache-maven-9.9.9/bin/mvn" <<'FAKE'
#!/bin/sh
if [ -n "${FAKE_MAVEN_ARGUMENT_LOG:-}" ]; then
  printf 'BEGIN\n' >> "$FAKE_MAVEN_ARGUMENT_LOG"
  printf '%s\n' "$@" >> "$FAKE_MAVEN_ARGUMENT_LOG"
fi
echo fake-mvn "$@"
FAKE
chmod +x "$T/build/apache-maven-9.9.9/bin/mvn"
(cd "$T/build" && zip -qr "$REPO/apache-maven-9.9.9-bin.zip" apache-maven-9.9.9)
(cd "$REPO" && sha512sum apache-maven-9.9.9-bin.zip | cut -d' ' -f1 > apache-maven-9.9.9-bin.zip.sha512)
cp "$ROOT/mvnw" "$T/p/mvnw"
PROPS="$T/p/.mvn/wrapper/maven-wrapper.properties"
echo "distributionUrl=file://$REPO/apache-maven-9.9.9-bin.zip" > "$PROPS"
run() { rm -rf "${T:?}/home"; cached "$@"; }
cached() { (cd "$T/p" && MAVEN_USER_HOME="$T/home" "$@" ./mvnw -q >/dev/null 2>&1); }
fail() { echo "FAIL: $*" >&2; exit 1; }
tamper_checksum() {
  # Always change the digest: a freshly built ZIP can already have a checksum starting with 0.
  local checksum replacement
  checksum=$(sed -n 's/^distributionSha512Sum=//p' "$PROPS")
  case "$checksum" in 0*) replacement=1 ;; *) replacement=0 ;; esac
  sed -i "s/^distributionSha512Sum=./distributionSha512Sum=$replacement/" "$PROPS"
}

run env MVNW_REQUIRE_PINNED_CHECKSUM=true && fail "strict mode accepted an unpinned distribution"
run env || fail "published checksum not accepted"
(cd "$T/p" && ./mvnw --pin-checksum >/dev/null 2>&1) || fail "pin failed"
grep -q '^distributionSha512Sum=[0-9a-f]\{128\}$' "$PROPS" || fail "checksum not pinned"
run env MVNW_REQUIRE_PINNED_CHECKSUM=true || fail "pinned checksum not accepted"
tamper_checksum
run env && fail "tampered distribution accepted"
[ ! -e "$T/home/wrapper/dists/apache-maven-9.9.9/apache-maven-9.9.9" ] || fail "tampered zip unpacked"
# strict mode is not bypassed by a distribution already in the cache
grep -v '^distributionSha512Sum=' "$PROPS" > "$T/props" && cp "$T/props" "$PROPS"
run env || fail "published checksum not accepted (cache warm-up)"
cached env MVNW_REQUIRE_PINNED_CHECKSUM=true && fail "strict mode ran a cached, unpinned distribution"
# a cache verified against another checksum is verified again (and a tampered download rejected)
(cd "$T/p" && ./mvnw --pin-checksum >/dev/null 2>&1) || fail "pin failed"
cached env MVNW_REQUIRE_PINNED_CHECKSUM=true || fail "cache verified against the same checksum rejected"
tamper_checksum
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
# Fresh-source model bootstrap uses only local install goals, preserving argument boundaries.
mkdir -p "$T/p/platform/axiz-dependencies" "$T/p/platform/axiz-bom"
touch "$T/p/platform/axiz-dependencies/pom.xml" "$T/p/platform/axiz-bom/pom.xml"
(cd "$T/p" && MAVEN_USER_HOME="$T/home" FAKE_MAVEN_ARGUMENT_LOG="$T/bootstrap-args" \
  ./mvnw --bootstrap '-Dmaven.repo.local=repository with spaces' -s 'settings with spaces.xml' verify >/dev/null)
python3 - "$T/bootstrap-args" "$T/p" <<'PYTEST'
import sys
from pathlib import Path
calls = [part.splitlines() for part in Path(sys.argv[1]).read_text().split('BEGIN\n')[1:]]
root = Path(sys.argv[2])
assert len(calls) == 3, calls
for call, module in zip(calls[:2], ['axiz-dependencies', 'axiz-bom']):
    assert call == ['-B', '-ntp', '-f', str(root / 'platform' / module / 'pom.xml'), 'install',
                    '-Dmaven.repo.local=repository with spaces', '-s', 'settings with spaces.xml'], call
assert calls[2] == ['-f', str(root / 'pom.xml'), '-Dmaven.repo.local=repository with spaces',
                    '-s', 'settings with spaces.xml', 'verify'], calls[2]
PYTEST
rm "$T/p/platform/axiz-bom/pom.xml"
(cd "$T/p" && MAVEN_USER_HOME="$T/home" ./mvnw --bootstrap verify >/dev/null 2>&1) \
  && fail "bootstrap accepted an incomplete framework checkout"
echo "mvnw: all checks passed"
