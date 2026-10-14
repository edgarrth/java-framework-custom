#!/usr/bin/env bash
# Installs this framework in the local Maven repository and generates sample projects with the
# generation engine (one per archetype by default, every combination with MODE=all) into
# target/samples. Used by CI to build the generated projects exactly as a team would.
set -euo pipefail
cd "$(dirname "$0")/../.."
MODE="${MODE:-defaults}"
revision=$(./mvnw -q -N help:evaluate -Dexpression=revision -DforceStdout)
./mvnw -B -ntp install -DskipTests
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:java \
  -Dexec.mainClass=pe.axiz.codegen.tools.SampleProjects \
  -Dexec.args="target/samples codegen/axiz-codegen/src/test/resources/contracts/orders.yaml ${revision} ${MODE}"
cat target/samples/modules.json
