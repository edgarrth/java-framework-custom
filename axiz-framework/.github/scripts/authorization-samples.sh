#!/usr/bin/env bash
# Requiere el framework actual instalado en local, como en el job de samples de CI.
set -euo pipefail
cd "$(dirname "$0")/../.."
revision=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").getroot().findtext("{*}properties/{*}revision"))')
mkdir -p target/authorization-acceptance
acceptance_root=$(mktemp -d "$PWD/target/authorization-acceptance/run.XXXXXX")
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:exec \
  -Dexec.executable=java -Dexec.classpathScope=runtime \
  "-Dexec.args=--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --class-path %classpath \"$PWD/.github/scripts/GenerateAuthorizationSamples.java\" \"$acceptance_root\" \"$PWD/codegen/axiz-codegen/src/test/resources/contracts/orders.yaml\" $revision"
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:exec \
  -Dexec.executable=java -Dexec.classpathScope=runtime \
  "-Dexec.args=--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --class-path %classpath \"$PWD/.github/scripts/GenerateAccessPolicySamples.java\" \"$acceptance_root\" $revision"
for stack in servlet reactive access-servlet access-reactive; do
  (
    cd "$acceptance_root/$stack"
    ./mvnw -B -ntp spotless:check test '-Dtest=*AuthorizationTest' \
      -Dsurefire.failIfNoSpecifiedTests=true
  )
done
