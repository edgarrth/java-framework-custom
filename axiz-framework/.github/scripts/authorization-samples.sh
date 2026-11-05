#!/usr/bin/env bash
# Requires the current framework installed locally, as in the samples CI job.
set -euo pipefail
cd "$(dirname "$0")/../.."
revision=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").getroot().findtext("{*}properties/{*}revision"))')
mkdir -p target/authorization-acceptance
acceptance_root=$(mktemp -d "$PWD/target/authorization-acceptance/run.XXXXXX")
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:exec \
  -Dexec.executable=java -Dexec.classpathScope=runtime \
  "-Dexec.args=--class-path %classpath \"$PWD/.github/scripts/GenerateAuthorizationSamples.java\" \"$acceptance_root\" \"$PWD/codegen/axiz-codegen/src/test/resources/contracts/orders.yaml\" $revision"
for stack in servlet reactive; do
  (
    cd "$acceptance_root/$stack"
    ./mvnw -B -ntp spotless:apply test '-Dtest=*AuthorizationTest' \
      -Dsurefire.failIfNoSpecifiedTests=true
  )
done
