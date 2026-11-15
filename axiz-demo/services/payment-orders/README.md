# Payment Orders

Owner of payment orders.

| | |
|---|---|
| Archetype | `business`: Business rules and ownership of domain data |
| Structure | `hexagonal` |
| Extensions | `cqrs` |
| Stack | `servlet` |
| Persistence | `aurora` |
| axiz | `2.0.0` |
| Base package | `pe.axiz.payments.orders` |

## In the demo

Owner of payment orders (CQRS on Aurora).

- Registering an order validates the creditor CCI check digits, assigns the processing date
  (business calendar and 18:00 cut-off), rejects possible duplicates, checks the daily limit per
  currency, numbers the operation (`OP` + daily sequence) and publishes
  `payment-order.created.v1` through the transactional outbox, all in one transaction.
- Creditor accounts are stored encrypted (KMS envelope keys) with a blind index for searches; the
  API returns them masked.
- payment-process moves orders through `PROCESSING → COMPLETED | REJECTED`; every change publishes
  `payment-order.status-changed.v1`. The audit trail goes to MSK.

## Encrypted columns

The AAD contexts of the encrypted columns are `payment_order.debtor_account` and
`payment_order.creditor_cci`; `payments-history` uses `payment_history.creditor_cci`.

The local demo is disposable: `scripts/demo.sh down` removes local volumes and all demo data, and
`scripts/demo.sh up` recreates the environment.

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

`ArchitectureTest` enforces the rules of the hexagonal structure and CQRS extension and the general axiz rules;
`PaymentOrdersApplicationTest` starts the service against real dependencies in containers.
`ContractExamplesTest` checks that every request example of the contract is a valid request.

## Run locally

```bash
local/dev-secrets.sh       # random development-only secrets in local/secrets (git-ignored)
docker compose up -d
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1   # LocalStack, not real credentials
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
build time into `pe.axiz.payments.orders.api.model` (never versioned). Controllers were generated once
as skeletons that answer `501 Not Implemented` until each operation is implemented.

- `axiz contract sync` adds skeletons for new operations, reports removed ones and checks breaking
  changes against the previous version (openapi-diff).
- Lint the contract with the axiz Spectral ruleset before publishing it.

| Operation | Method | Path |
|---|---|---|
| `createPaymentOrder` | POST | `/v1/payment-orders` |
| `listPaymentOrders` | GET | `/v1/payment-orders` |
| `getPaymentOrder` | GET | `/v1/payment-orders/{orderId}` |
| `changePaymentOrderStatus` | POST | `/v1/payment-orders/{orderId}/status` |

## Structure

- `pe.axiz.payments.orders.domain`: Domain model: plain Java, no framework dependencies.
- `pe.axiz.payments.orders.application.command`: Commands and their handlers (write side).
- `pe.axiz.payments.orders.application.query`: Queries and their handlers (read side).
- `pe.axiz.payments.orders.application.port.out`: Outbound ports implemented by adapters.
- `pe.axiz.payments.orders.adapter.in.rest`: REST adapter generated from the OpenAPI contract.
- `pe.axiz.payments.orders.adapter.out`: Outbound adapters: persistence, HTTP clients, messaging.
- `pe.axiz.payments.orders.config`: Wiring of handlers, ports and adapters.

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
| `audit` | `axiz-audit-spring-boot-starter` | @Auditable business audit trail, sanitized by sensitivity, published asynchronously |
| `jpa` | `axiz-jpa-spring-boot-starter` | Aurora PostgreSQL with JPA, Flyway, AWS JDBC wrapper (IAM, failover, read/write splitting), auditing and encrypted columns |
| `secrets` | `axiz-secrets-spring-boot-starter` | Secrets from the mounted ExternalSecret directory, environment, Secrets Manager or Parameter Store, cached |
| `crypto` | `axiz-crypto-spring-boot-starter` | Field encryption (JPA and DynamoDB converters, rotating keys from the secret store), KMS envelope encryption, blind index, JOSE, password hashing |
| `outbox` | `axiz-outbox-spring-boot-starter` | Transactional outbox in Aurora with a relay to MSK, SNS or SQS (at least once) |
| `messaging-kafka` | `axiz-messaging-spring-boot-starter` | CloudEvents over Amazon MSK with IAM auth, retries, DLQ and trace propagation |
| `sequence` | `axiz-sequence-spring-boot-starter` | Business sequences (prefix, width, daily or monthly reset) on JDBC or DynamoDB |
| `audit-kafka` | `axiz-audit-spring-boot-starter` | Audit events published to MSK |

Add more with `axiz add <capability>`: it adds the starter, its properties, the ConfigMap keys and a
usage example.

## Configuration

- `application.yaml` holds defaults only. Environment values live in the ConfigMaps `axiz-common`,
  `payment-orders` and `payment-orders-errors`, mounted as files by the chart
  (`deploy/helm/payment-orders/values-<env>.yaml`, keys `config` and `errors`).
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
- `axiz.crypto.kms.key-id`
- `axiz.crypto.fields.current-key-id`
- `axiz.crypto.fields.key-ids`
- `spring.kafka.bootstrap-servers`
- `axiz.messaging.destinations`
- `axiz.audit.kafka.topic`

## Deployment

`deploy/helm/payment-orders` contains the chart: Deployment with probes, Service, HPA, PDB,
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
