#!/usr/bin/env bash
# Instala este framework en el repositorio Maven local y genera proyectos de ejemplo con el motor de
# generación (uno por arquetipo por defecto, todas las combinaciones con MODE=all) en
# target/samples. CI construye los proyectos generados exactamente como lo haría un equipo.
set -euo pipefail
cd "$(dirname "$0")/../.."
MODE="${MODE:-defaults}"
./mvnw --bootstrap
revision=$(./mvnw -q -N help:evaluate -Dexpression=revision -DforceStdout)
./mvnw -B -ntp install -DskipTests
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:java \
  -Dexec.mainClass=pe.axiz.codegen.tools.SampleProjects \
  -Dexec.args="target/samples codegen/axiz-codegen/src/test/resources/contracts/orders.yaml ${revision} ${MODE}"
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:java \
  -Dexec.mainClass=pe.axiz.codegen.tools.SampleProjects \
  -Dexec.args="target/audit-samples codegen/axiz-codegen/src/test/resources/contracts/orders.yaml ${revision} audit"
# Las aserciones internas pertenecen al harness del framework, no a las plantillas de los consumidores.
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:exec \
  -Dexec.executable=java -Dexec.classpathScope=runtime \
  "-Dexec.args=--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --class-path %classpath \"$PWD/.github/scripts/GenerateAuditAcceptanceChecks.java\" \"$PWD/target/audit-samples\""
cat target/samples/modules.json
