# Payment Process

Payment saga.

| | |
|---|---|
| Archetype | `business`: Business rules and ownership of domain data |
| Structure | `hexagonal` |
| Extensions | – |
| Stack | `servlet` |
| Persistence | `aurora` |
| axiz | `2.0.0` |
| Base package | `pe.axiz.payments.process` |

## In the demo

Orchestrates each payment with a saga (hexagonal style, state on Aurora).

1. `processing`: the order moves to PROCESSING (undo: REJECTED).
2. `debit`: banking-core debits the customer (undo: reversal).
3. `transfer`: banking-core sends the transfer (pivot: never undone).
4. `complete`: the order moves to COMPLETED (never fails the saga; settlement reconciles it).

Processes start from `payment-order.created.v1` (Kafka). Every downstream call carries a key
derived from the order and the step, so retries, redeliveries and resumed sagas are idempotent.
`SagaRecoveryJob` (ShedLock) resumes sagas interrupted by a crash.

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

`ArchitectureTest` enforces the rules of the `hexagonal` style and the general axiz rules;
`PaymentProcessApplicationTest` starts the service against real dependencies in containers.
`ContractExamplesTest` checks that every request example of the contract is a valid request.

## Run locally

```bash
local/dev-secrets.sh       # random development-only secrets in local/secrets (git-ignored)
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Or `axiz local up` / `axiz local down`. Traces, metrics and logs are in Grafana at
<http://localhost:3000> (OpenTelemetry collector on ports 4317/4318).
Keycloak (<http://localhost:8180>, realm `axiz`) issues local tokens:

```bash
curl -s -d grant_type=client_credentials -d client_id=local-client -d client_secret=local-only \
  http://localhost:8180/realms/axiz/protocol/openid-connect/token
```

## Contract first

`src/main/openapi/openapi.yaml` is the source of truth. Request and response models are generated at
build time into `pe.axiz.payments.process.api.model` (never versioned). Controllers were generated once
as skeletons that answer `501 Not Implemented` until each operation is implemented.

- `axiz contract sync` adds skeletons for new operations, reports removed ones and checks breaking
  changes against the previous version (openapi-diff).
- Lint the contract with the axiz Spectral ruleset before publishing it.

| Operation | Method | Path |
|---|---|---|
| `startPaymentProcess` | POST | `/v1/payment-processes` |
| `getPaymentProcess` | GET | `/v1/payment-processes/{processId}` |

## Structure

- `pe.axiz.payments.process.domain`: Domain model: plain Java, no framework dependencies.
- `pe.axiz.payments.process.application.usecase`: Use cases: one class per operation, transactional.
- `pe.axiz.payments.process.application.port.out`: Outbound ports implemented by adapters.
- `pe.axiz.payments.process.adapter.in.rest`: REST adapter generated from the OpenAPI contract.
- `pe.axiz.payments.process.adapter.out`: Outbound adapters: persistence, HTTP clients, messaging.
- `pe.axiz.payments.process.config`: Wiring of use cases, ports and adapters.

## Capabilities

| Capability | Starter | What it brings |
|---|---|---|
| `foundation` | `axiz-foundation-spring-boot-starter` | Clock and business calendar (zone, weekends, holidays), JSON and validation defaults |
| `web` | `axiz-web-spring-boot-starter` | Request metadata headers, correlation, pagination, API versioning, CORS and JSON conventions |
| `errors` | `axiz-errors-spring-boot-starter` | RFC 9457 problem details, error catalog (ConfigMap `APP_NAME-errors`), external error mapping |
| `logging` | `axiz-logging-spring-boot-starter` | Structured JSON logs with trace and request context, HTTP access log with masking |
| `observability` | `axiz-observability-spring-boot-starter` | OpenTelemetry traces and metrics through Micrometer (OTLP), no Java agent |
| `metrics` | `axiz-metrics-spring-boot-starter` | Generic event metrics (count, duration, amounts by currency, values) with bounded tags |
| `config` | `axiz-config-spring-boot-starter` | ConfigMaps mounted as files (axiz-common, `APP_NAME`, `APP_NAME-errors`; no Kubernetes API access), Parameter Store, required keys check |
| `i18n` | `axiz-i18n-spring-boot-starter` | Localized messages and problem details (es-PE by default) from messages*.properties |
| `sensitive` | `axiz-sensitive-spring-boot-starter` | Masking of sensitive fields in responses, logs and audit by sensitivity level |
| `security` | `axiz-security-spring-boot-starter` | OAuth2 resource server (trusted issuers, audiences, claims), caller context, rate limit |
| `jpa` | `axiz-jpa-spring-boot-starter` | Aurora PostgreSQL with JPA, Flyway, AWS JDBC wrapper (IAM, failover, read/write splitting), auditing and encrypted columns |
| `saga` | `axiz-saga-spring-boot-starter` | Orchestrated sagas with persisted state, step retries and compensations |
| `resilience` | `axiz-resilience-spring-boot-starter` | Timeouts, retry with backoff, circuit breaker and bulkhead policies by name |
| `http-client` | `axiz-http-client-spring-boot-starter` | Declarative HTTP clients per name with timeouts, retries, circuit breaker, OAuth2 client credentials and header propagation |
| `messaging-kafka` | `axiz-messaging-spring-boot-starter` | CloudEvents over Amazon MSK with IAM auth, retries, DLQ and trace propagation |
| `tenancy` | `axiz-tenancy-spring-boot-starter` | Multi-tenancy from the token or a trusted header, propagated to logs, metrics and data |

Add more with `axiz add <capability>`: it adds the starter, its properties, the ConfigMap keys and a
usage example.

## Configuration

- `application.yaml` holds defaults only. Environment values live in the ConfigMaps `axiz-common`,
  `payment-process` and `payment-process-errors`, mounted as files by the chart
  (`deploy/helm/payment-process/values-<env>.yaml`, keys `config` and `errors`).
- Secrets never go to the repository nor to ConfigMaps: they come from AWS Secrets Manager through
  the ExternalSecret and are mounted as files in `/var/run/secrets/axiz`.
- AWS access uses the pod identity (IRSA or EKS Pod Identity); there are no AWS keys anywhere.

Keys each environment must define:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `spring.datasource.url`
- `spring.datasource.username`
- `axiz.jpa.aws-wrapper.enabled`
- `axiz.jpa.aws-wrapper.iam`
- `axiz.http-client.clients`
- `spring.kafka.bootstrap-servers`
- `axiz.messaging.destinations`

## Deployment

`deploy/helm/payment-process` contains the chart: Deployment with probes, Service, HPA, PDB,
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
