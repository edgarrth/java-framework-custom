#!/usr/bin/env bash
# A real reactive service checks generated contracts, BlockHound, AOT and native bootstrap.
set -euo pipefail
cd "$(dirname "$0")/../.."
./mvnw --bootstrap
revision=$(cat version.txt)
./mvnw -B -ntp install -DskipTests
mkdir -p "$PWD/target/reactive-native-acceptance"
acceptance_root=$(mktemp -d "$PWD/target/reactive-native-acceptance/run.XXXXXX")
fixture="$acceptance_root/sample-native-orders"
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:exec \
  -Dexec.executable=java \
  -Dexec.classpathScope=runtime \
  "-Dexec.args=--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --class-path %classpath \"$PWD/.github/scripts/GenerateReactiveNativeSample.java\" \"$fixture\" \"$PWD/codegen/axiz-codegen/src/test/resources/contracts/orders.yaml\" \"$PWD/codegen/axiz-codegen/src/test/resources/events.yaml\" $revision"
(
  cd "$fixture"
  ./mvnw -B -ntp spotless:check verify -Daxiz.it.requireDocker=true
  ./mvnw -B -ntp -Pnative -DskipTests package
)
# Check generated definitions before spending time compiling the ELF; no runtime annotation scans.
python3 .github/scripts/check-native-reactive-sample.py --module "$fixture" --runtime jvm-aot
(
  cd "$fixture"
  ./mvnw -B -ntp -Pnative -DskipTests native:compile
)
python3 .github/scripts/check-native-reactive-sample.py --module "$fixture"
