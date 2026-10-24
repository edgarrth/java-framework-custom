# Notifications Processor

Customer notifications and audit archive.

| | |
|---|---|
| Archetype | `event-processor`: Consumes and reacts to events, without a public API |
| Style | `eda` |
| Stack | `servlet` |
| Persistence | `dynamodb` |
| axiz | `0.1.0-SNAPSHOT` |
| Base package | `pe.axiz.payments.notifications` |

## In the demo

Event processor (EDA style, DynamoDB).

- `payment-order.status-changed.v1` (Kafka): notifies COMPLETED and REJECTED payments through the
  customer's channel (contacts table), publishing `notification.requested.v1` to SNS.
- `pe.axiz.audit.event.v1` (SQS): archives the audit trail on S3 (`audit/yyyy/MM/dd/<service>/`)
  with a daily manifest updated under a DynamoDB lock.
- Redeliveries are removed by the inbox (DynamoDB); failures go to the dead-letter topic/queue.

## Requirements

- JDK 25 (Maven comes with the wrapper `./mvnw`)
- Docker, for `docker compose` and the Testcontainers tests
- Access to the Nexus repository that publishes axiz

## Build and test

```bash
./mvnw verify                 # enforcer rules, tests and architecture rules
./mvnw spotless:apply         # formats the code (run it before pushing)
./mvnw -Pquality verify       # CI gate: + format check, Checkstyle, Error Prone + NullAway, SpotBugs/FindSecBugs,
                              # dependency convergence and coverage thresholds
./mvnw -Pnative native:compile  # GraalVM native image
```

`ArchitectureTest` enforces the rules of the `eda` style and the general axiz rules;
`NotificationsProcessorApplicationTest` starts the service against real dependencies in containers.

## Run locally

```bash
local/dev-secrets.sh       # random development-only secrets in local/secrets (git-ignored)
docker compose up -d
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1   # LocalStack, not real credentials
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Or `axiz local up` / `axiz local down`. Traces, metrics and logs are in Grafana at
http://localhost:3000 (OpenTelemetry collector on ports 4317/4318).

## Structure

- `pe.axiz.payments.notifications.handler`: Event handlers, one per event type.
- `pe.axiz.payments.notifications.event`: Event payloads.

## Capabilities

| Capability | Starter | What it brings |
|---|---|---|
| `foundation` | `axiz-foundation-spring-boot-starter` | Clock and business calendar (zone, weekends, holidays), JSON and validation defaults |
| `web` | `axiz-web-spring-boot-starter` | Request metadata headers, correlation, pagination, API versioning, CORS and JSON conventions |
| `errors` | `axiz-errors-spring-boot-starter` | RFC 9457 problem details, error catalog (ConfigMap <app>-errors), external error mapping |
| `logging` | `axiz-logging-spring-boot-starter` | Structured JSON logs with trace and request context, HTTP access log with masking |
| `observability` | `axiz-observability-spring-boot-starter` | OpenTelemetry traces and metrics through Micrometer (OTLP), no Java agent |
| `metrics` | `axiz-metrics-spring-boot-starter` | Generic event metrics (count, duration, amounts by currency, values) with bounded tags |
| `config` | `axiz-config-spring-boot-starter` | ConfigMaps mounted as files (axiz-common, <app>, <app>-errors; no Kubernetes API access), Parameter Store, required keys check |
| `sensitive` | `axiz-sensitive-spring-boot-starter` | Masking of sensitive fields in responses, logs and audit by sensitivity level |
| `messaging-kafka` | `axiz-messaging-spring-boot-starter` | CloudEvents over Amazon MSK with IAM auth, retries, DLQ and trace propagation |
| `dynamodb` | `axiz-dynamodb-spring-boot-starter` | DynamoDB enhanced client, repositories with cursor pagination and optimistic locking |
| `messaging-sqs` | `axiz-messaging-spring-boot-starter` | CloudEvents over SQS (standard and FIFO) consumers and publishers with DLQ |
| `messaging-sns` | `axiz-messaging-spring-boot-starter` | CloudEvents publishing to SNS topics (fan-out) |
| `inbox` | `axiz-inbox-spring-boot-starter` | Consumer de-duplication (inbox) with leases and retention, JDBC or DynamoDB |
| `notification` | `axiz-notification-spring-boot-starter` | Templated notifications (email, SMS, push) published as events |
| `storage-s3` | `axiz-storage-spring-boot-starter` | Object storage on S3 (streaming, presigned URLs, metadata), observed |
| `lock` | `axiz-lock-spring-boot-starter` | Distributed locks (in memory only in local/test; shared environments need lock-redis or lock-dynamodb) |
| `lock-dynamodb` | `axiz-lock-spring-boot-starter` | Distributed locks on DynamoDB (leases) |

Add more with `axiz add <capability>`: it adds the starter, its properties, the ConfigMap keys and a
usage example.

## Configuration

- `application.yaml` holds defaults only. Environment values live in the ConfigMaps `axiz-common`,
  `notifications-processor` and `notifications-processor-errors`, mounted as files by the chart
  (`deploy/helm/notifications-processor/values-<env>.yaml`, keys `config` and `errors`).
- Secrets never go to the repository nor to ConfigMaps: they come from AWS Secrets Manager through
  the ExternalSecret and are mounted as files in `/var/run/secrets/axiz`.
- AWS access uses the pod identity (IRSA or EKS Pod Identity); there are no AWS keys anywhere.

Keys each environment must define:

- `axiz.foundation.calendar.holidays`
- `spring.kafka.bootstrap-servers`
- `axiz.messaging.destinations`
- `axiz.dynamodb.table-prefix`
- `axiz.messaging.sqs.queues`
- `axiz.storage.bucket`

## Deployment

`deploy/helm/notifications-processor` contains the chart: Deployment with probes, Service, HPA, PDB,
ConfigMaps, ExternalSecret, ServiceAccount, RBAC to read the ConfigMaps and a NetworkPolicy.
The pipelines in `.github/workflows` call the shared axiz workflows, pinned to an immutable release (`@v1.0.0`; Renovate pins its commit and proposes new releases):

- `ci.yaml`: pull requests run the shared checks and the build with every quality gate; `main`
  builds the image (Jib, signed with cosign) and deploys it to dev and then qa; a tag `v*` deploys
  to prod after the approval of the `prod` environment.
- Deployments are GitOps: the pipeline records the release (chart commit, image tag and digest) in
  the GitOps repository and Argo CD applies it. `cd.yaml` redeploys or rolls back an existing image.
- `release.yaml`: release-please keeps a release pull request with the next version and the
  changelog; merging it creates the tag.

## Decisions

