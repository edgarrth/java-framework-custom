#!/usr/bin/env bash
# Solo verificación semántica de la configuración; el build y la validación de formato se ejecutan en el step previo de CI.
set -euo pipefail
cd "$(dirname "$0")/../.."
framework_root=$PWD
for project in target/samples/*/; do
  if [[ ! -f "$project/src/main/resources/application.yaml" ]]; then continue; fi
  (
    cd "$project"
    ./mvnw -B -ntp -q org.codehaus.mojo:exec-maven-plugin:3.5.1:exec \
      -Dexec.executable=java -Dexec.classpathScope=test \
      "-Dexec.args=--class-path %classpath \"$framework_root/.github/scripts/CheckKafkaIamProfiles.java\" \"$framework_root/$project\""
  )
done
