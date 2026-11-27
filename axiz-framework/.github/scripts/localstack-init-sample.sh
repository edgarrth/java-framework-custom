#!/usr/bin/env bash
# Se ejecuta dos veces el init local generado contra una emulación real de AWS; se exige una única clave KMS reutilizable.
set -euo pipefail
cd "$(dirname "$0")/../.."
revision=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").getroot().findtext("{*}properties/{*}revision"))')
mkdir -p target/local-init-acceptance
fixture=$(mktemp -d "$PWD/target/local-init-acceptance/run.XXXXXX")
./mvnw -B -ntp -q -pl codegen/axiz-codegen \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:exec \
  -Dexec.executable=java -Dexec.classpathScope=runtime \
  "-Dexec.args=--class-path %classpath \"$PWD/.github/scripts/GenerateLocalInfrastructureSample.java\" \"$fixture\" \"$PWD/codegen/axiz-codegen/src/test/resources/contracts/orders.yaml\" $revision"
image=$(cat "$fixture/image.txt")
# La imagen local se construye desde la receta del framework cuando todavía no existe.
if ! docker image inspect "$image" >/dev/null 2>&1; then
  docker build --tag "$image" quality/axiz-test-containers/src/main/resources/pe/axiz/quality/containers/images/localstack
fi
container=$(docker run --rm -d -e SERVICES=s3,dynamodb,sqs,sns,kms "$image")
trap 'docker rm -f "$container" >/dev/null' EXIT
ready=false
for attempt in {1..30}; do
  if docker exec "$container" awslocal s3api list-buckets >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then docker logs "$container"; exit 1; fi
docker cp "$fixture/init.sh" "$container:/tmp/axiz-init.sh" >/dev/null
docker exec "$container" bash /tmp/axiz-init.sh
first=$(docker exec "$container" awslocal kms describe-key --key-id alias/local --query KeyMetadata.KeyId --output text)
docker exec "$container" bash /tmp/axiz-init.sh
second=$(docker exec "$container" awslocal kms describe-key --key-id alias/local --query KeyMetadata.KeyId --output text)
keys=$(docker exec "$container" awslocal kms list-keys --query 'length(Keys)' --output text)
[[ -n "$first" && "$first" == "$second" && "$keys" == 1 ]]
docker exec "$container" awslocal dynamodb describe-table --table-name local-axiz-idempotency >/dev/null
docker exec "$container" awslocal sqs get-queue-url --queue-name infra-orders-audit >/dev/null
docker exec "$container" awslocal s3api head-bucket --bucket local-bucket
printf '{"passed":true,"runs":2,"kmsKeys":%s,"sameKey":true}\n' "$keys" > "$fixture/result.json"
echo "Generated init passed twice; S3/DynamoDB/SQS/SNS provisioned, one unchanged KMS key: $fixture"
