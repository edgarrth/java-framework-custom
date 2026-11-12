#!/usr/bin/env bash
# Installs this framework in the local Maven repository and generates sample projects with the
# generation engine (one per archetype by default, every combination with MODE=all) into
# target/samples. Used by CI to build the generated projects exactly as a team would.
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
# Internal assertions belong to the framework harness, not the consumer templates.
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:exec \
  -Dexec.executable=java -Dexec.classpathScope=runtime \
  "-Dexec.args=--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --class-path %classpath \"$PWD/.github/scripts/GenerateAuditAcceptanceChecks.java\" \"$PWD/target/audit-samples\""
if [ "$MODE" = defaults ]; then
  validation_env=$(mktemp -d "${TMPDIR:-/tmp}/axiz-sample-validation.XXXXXX")
  trap 'rm -rf "$validation_env"' EXIT
  python3 -m venv "$validation_env"
  "$validation_env/bin/python" -m pip install --disable-pip-version-check --quiet \
    -r .github/scripts/requirements.txt
  "$validation_env/bin/python" .github/scripts/check-sample-workflows.py \
    --samples target/samples/modules.json
fi
cat target/samples/modules.json
