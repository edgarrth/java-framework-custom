# Settlement Batch

Daily settlement and reconciliation.

| | |
|---|---|
| Archetype | `batch`: Scheduled batch processing |
| Structure | `batch` |
| Extensions | None |
| Stack | `servlet` |
| Persistence | `aurora` |
| axiz | `2.0.0` |
| Base package | `pe.axiz.payments.settlement` |

## In the demo

Kubernetes CronJob that loads the daily settlement file of the payment network (fixed width,
`in/settlement/NET-SETTLE-<yyyyMMdd>.txt`, previous business day by default) into Aurora:

1. `validate`: trailer control totals (count and amount) before loading anything.
2. `load`: chunks of 500 lines, idempotent insert by end-to-end id, invalid CCIs filtered.
3. `summary`: totals per currency and status as metrics and `out/settlement/<file>.summary.json`.

One job instance per file (a file is never loaded twice) and a ShedLock cluster lock so manual and
scheduled runs never overlap. The pod exits with the job's exit code.

## Requirements

- JDK 25 (Maven comes with the wrapper `./mvnw`)
- Docker, for `docker compose` and the Testcontainers tests
- Access to the GitHub Packages repository that publishes axiz

## Build and test

```bash
./mvnw verify                 # enforcer rules, tests and architecture rules
./mvnw spotless:apply         # formats the code (run it before pushing)
./mvnw -Pquality verify       # CI gate: + format check, Checkstyle, Error Prone + NullAway, SpotBugs/FindSecBugs,
                              # dependency convergence and coverage thresholds
./mvnw -Pnative native:compile  # GraalVM native image
```

`ArchitectureTest` enforces the rules of the `batch` style and the general axiz rules;
`SettlementBatchApplicationTest` starts the service against real dependencies in containers.

## Run locally

```bash
local/dev-secrets.sh       # random development-only secrets in local/secrets (git-ignored)
docker compose up -d
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1   # LocalStack, not real credentials
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Or `axiz local up` / `axiz local down`. Traces, metrics and logs are in Grafana at
<http://localhost:3000> (OpenTelemetry collector on ports 4317/4318).

## Structure

- `pe.axiz.payments.settlement.job`: Jobs, steps, readers, processors and writers.

## Capabilities

| Capability | Starter | What it brings |
|---|---|---|
| `foundation` | `axiz-foundation-spring-boot-starter` | Clock and business calendar (zone, weekends, holidays), JSON and validation defaults |
| `errors` | `axiz-errors-spring-boot-starter` | RFC 9457 problem details, error catalog (ConfigMap `APP_NAME-errors`), external error mapping |
| `logging` | `axiz-logging-spring-boot-starter` | Structured JSON logs with trace and request context, HTTP access log with masking |
| `observability` | `axiz-observability-spring-boot-starter` | OpenTelemetry traces and metrics through Micrometer (OTLP), no Java agent |
| `metrics` | `axiz-metrics-spring-boot-starter` | Generic event metrics (count, duration, amounts by currency, values) with bounded tags |
| `config` | `axiz-config-spring-boot-starter` | ConfigMaps mounted as files (axiz-common, `APP_NAME`, `APP_NAME-errors`; no Kubernetes API access), Parameter Store, required keys check |
| `storage-s3` | `axiz-storage-spring-boot-starter` | Object storage on S3 (streaming, presigned URLs, metadata), observed |
| `audit` | `axiz-audit-spring-boot-starter` | @Auditable business audit trail, sanitized by sensitivity, published asynchronously |
| `jpa` | `axiz-jpa-spring-boot-starter` | Aurora PostgreSQL with JPA, Flyway, AWS JDBC wrapper (IAM, failover, read/write splitting), auditing and encrypted columns |
| `scheduling` | `axiz-scheduling-spring-boot-starter` | @Scheduled jobs with ShedLock (JDBC or DynamoDB) so only one replica runs them |

Add more with `axiz add <capability>`: it adds the starter, its properties, the ConfigMap keys and a
usage example.

## Configuration

- `application.yaml` holds defaults only. Environment values live in the ConfigMaps `axiz-common`,
  `settlement-batch` and `settlement-batch-errors`, mounted as files by the chart
  (`deploy/helm/settlement-batch/values-<env>.yaml`, keys `config` and `errors`).
- Secrets never go to the repository nor to ConfigMaps: they come from AWS Secrets Manager through
  the ExternalSecret and are mounted as files in `/var/run/secrets/axiz`.
- AWS access uses the pod identity (IRSA or EKS Pod Identity); there are no AWS keys anywhere.

Keys each environment must define:

- `axiz.foundation.calendar.holidays`
- `axiz.storage.bucket`
- `spring.datasource.url`
- `spring.datasource.username`
- `axiz.jpa.aws-wrapper.enabled`
- `axiz.jpa.aws-wrapper.iam`

## Deployment

`deploy/helm/settlement-batch` contains the chart: CronJob,
ConfigMaps mounted as files, ExternalSecret, ServiceAccount and a NetworkPolicy. Pods do not
require Kubernetes API permissions to read their mounted configuration.
The pipelines in `.github/workflows` call the shared axiz workflows, pinned to an immutable release (`@axiz-workflows-vX.Y.Z`; Renovate proposes grouped updates to the next immutable release tag):

- `ci.yaml`: pull requests run the shared checks and the build with every quality gate; `main`
  builds the image (Jib, signed with cosign) and deploys it to dev and then qa; a tag `v*` deploys
  to prod after the approval of the `prod` environment.
- Deployments are GitOps: the pipeline records the release (chart commit, image tag and digest) in
  the GitOps repository and Argo CD applies it. `cd.yaml` redeploys or rolls back an existing image.
- `release.yaml`: release-please keeps a release pull request with the next version and the
  changelog; merging it creates the tag.

## Decisions
