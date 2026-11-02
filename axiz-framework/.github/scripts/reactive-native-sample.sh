#!/usr/bin/env bash
# A real reactive service checks generated contracts, BlockHound, AOT and native bootstrap.
set -euo pipefail
cd "$(dirname "$0")/../.."
./mvnw --bootstrap
revision=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").getroot().findtext("{*}properties/{*}revision"))')
./mvnw -B -ntp install -DskipTests
mkdir -p "$PWD/target/reactive-native-acceptance"
acceptance_root=$(mktemp -d "$PWD/target/reactive-native-acceptance/run.XXXXXX")
fixture="$acceptance_root/sample-native-orders"
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:exec \
  -Dexec.executable=java \
  -Dexec.classpathScope=runtime \
  "-Dexec.args=--class-path %classpath \"$PWD/.github/scripts/GenerateReactiveNativeSample.java\" \"$fixture\" \"$PWD/codegen/axiz-codegen/src/test/resources/contracts/orders.yaml\" \"$PWD/codegen/axiz-codegen/src/test/resources/events.yaml\" $revision"
(
  cd "$fixture"
  ./mvnw -B -ntp spotless:apply verify -Daxiz.it.requireDocker=true
  ./mvnw -B -ntp -Pnative -DskipTests package native:compile
)
python3 .github/scripts/check-native-reactive-sample.py --module "$fixture"
