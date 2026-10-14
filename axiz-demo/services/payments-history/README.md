# Payments History

Payment history of each customer: read model projected from the payment order events over R2DBC.

| | |
|---|---|
| Archetype | `business`: Owner of a domain and its data; business rules |
| Style | `cqrs` |
| Stack | `reactive` |
| Persistence | `aurora` |
| axiz | `0.1.0-SNAPSHOT` |
| Base package | `pe.axiz.payments.history` |

## What it shows

The reactive side of axiz on Aurora PostgreSQL, end to end and without blocking a thread:

| Piece | Where |
|---|---|
| Kafka consumers that feed the **reactive command bus** (`ReactiveTypedEventHandler`) | `adapter/in/messaging` |
| Reactive CQRS: `ReactiveCommandHandler` / `ReactiveQueryHandler`, pipeline with span, validation, **reactive transaction** (R2DBC), metrics | `application` |
| Idempotent, order-tolerant projection (an old status never replaces a newer one) | `domain/PaymentHistory` |
| Spring Data R2DBC: `AuditableRow` (UUIDv7, `@Version`, auditing), `EncryptedText` and `Money` columns | `adapter/out/persistence` |
| R2DBC inbox (`axiz.inbox.store: r2dbc`), Flyway over JDBC, IAM tokens in AWS (`axiz.r2dbc.iam.enabled`) | `application.yaml`, `db/migration` |
| WebFlux API generated from the contract, latest change first, masked account | `adapter/in/rest` |

The read model is eventually consistent with payments-business: a payment that was just paid may
still show `RECEIVED` or `PROCESSING` for a moment, and `GET /v1/payments/{id}` answers 404 until
its first event is projected.

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

`ArchitectureTest` enforces the rules of the `cqrs` style and the general axiz rules;
`PaymentsHistoryApplicationTest` starts the service against real dependencies in containers.

## Run locally

```bash
local/dev-secrets.sh       # random development-only secrets in local/secrets (git-ignored)
docker compose up -d
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1   # LocalStack, not real credentials
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Or `axiz local up` / `axiz local down`. Traces, metrics and logs are in Grafana at
http://localhost:3000 (OpenTelemetry collector on ports 4317/4318).
Keycloak (http://localhost:8180, realm `axiz`) issues local tokens:

```bash
curl -s -d grant_type=client_credentials -d client_id=local-client -d client_secret=local-only \
  http://localhost:8180/realms/axiz/protocol/openid-connect/token
```

## Contract first

`src/main/openapi/openapi.yaml` is the source of truth. Request and response models are generated at
build time into `pe.axiz.payments.history.api.model` (never versioned). Controllers were generated once
as skeletons that answer `501 Not Implemented` until each operation is implemented.

- `axiz contract sync` adds skeletons for new operations, reports removed ones and checks breaking
  changes against the previous version (openapi-diff).
- Lint the contract with the axiz Spectral ruleset before publishing it.

| Operation | Method | Path |
|---|---|---|
| `listCustomerPayments` | GET | `/v1/customers/{customerId}/payments` |
| `getPayment` | GET | `/v1/payments/{paymentId}` |

## Structure

- `pe.axiz.payments.history.domain`: Domain model: plain Java, no framework dependencies.
- `pe.axiz.payments.history.application.command`: Commands and their handlers (write side).
- `pe.axiz.payments.history.application.query`: Queries and their handlers (read side).
- `pe.axiz.payments.history.application.port.out`: Outbound ports implemented by adapters.
- `pe.axiz.payments.history.adapter.in.rest`: REST adapter generated from the OpenAPI contract.
- `pe.axiz.payments.history.adapter.out`: Outbound adapters: persistence, HTTP clients, messaging.
- `pe.axiz.payments.history.config`: Wiring of handlers, ports and adapters.

## Capabilities

| Capability | Starter | What it brings |
|---|---|---|
| `foundation` | `axiz-foundation-spring-boot-starter` | Clock and business calendar (zone, weekends, holidays), JSON and validation defaults |
| `web` | `axiz-web-spring-boot-starter` | Request metadata headers, correlation, pagination, API versioning, CORS and JSON conventions |
| `errors` | `axiz-errors-spring-boot-starter` | RFC 9457 problem details, error catalog (ConfigMap <app>-errors), external error mapping |
| `logging` | `axiz-logging-spring-boot-starter` | Structured JSON logs with trace and request context, HTTP access log with masking |
| `observability` | `axiz-observability-spring-boot-starter` | OpenTelemetry traces and metrics through Micrometer (OTLP), no Java agent |
| `metrics` | `axiz-metrics-spring-boot-starter` | Generic event metrics (count, duration, amounts by currency, values) with bounded tags |
| `config` | `axiz-config-spring-boot-starter` | ConfigMaps through Spring Cloud Kubernetes (axiz-common, <app>, <app>-errors), Parameter Store, required keys check |
| `i18n` | `axiz-i18n-spring-boot-starter` | Localized messages and problem details (es-PE by default) from messages*.properties |
| `sensitive` | `axiz-sensitive-spring-boot-starter` | Masking of sensitive fields in responses, logs and audit by sensitivity level |
| `security` | `axiz-security-spring-boot-starter` | OAuth2 resource server (trusted issuers, audiences, claims), caller context, rate limit |
| `audit` | `axiz-audit-spring-boot-starter` | @Auditable business audit trail, sanitized by sensitivity, published asynchronously |
| `r2dbc` | `axiz-r2dbc-spring-boot-starter` | Aurora PostgreSQL for reactive services over R2DBC (non-blocking), Flyway, IAM tokens, auditing, encrypted and money columns, reader endpoint |
| `inbox` | `axiz-inbox-spring-boot-starter` | Consumer de-duplication (inbox) with leases and retention, JDBC, R2DBC or DynamoDB |
| `messaging-kafka` | `axiz-messaging-spring-boot-starter` | CloudEvents over Amazon MSK with IAM auth, retries, DLQ and trace propagation |
| `secrets` | `axiz-secrets-spring-boot-starter` | Secrets from the mounted ExternalSecret directory, environment, Secrets Manager or Parameter Store, cached |
| `crypto` | `axiz-crypto-spring-boot-starter` | Field encryption (JPA and DynamoDB converters, rotating keys from the secret store), KMS envelope encryption, blind index, JOSE, password hashing |

Add more with `axiz add <capability>`: it adds the starter, its properties, the ConfigMap keys and a
usage example.

## Configuration

- `application.yaml` holds defaults only. Environment values live in the ConfigMaps `axiz-common`,
  `payments-history` and `payments-history-errors`, read by Spring Cloud Kubernetes
  (`deploy/helm/payments-history/values-<env>.yaml`, keys `config` and `errors`).
- Secrets never go to the repository nor to ConfigMaps: they come from AWS Secrets Manager through
  the ExternalSecret and are mounted as files in `/var/run/secrets/axiz`.
- AWS access uses the pod identity (IRSA or EKS Pod Identity); there are no AWS keys anywhere.

Keys each environment must define:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `spring.r2dbc.url`
- `spring.r2dbc.username`
- `axiz.r2dbc.iam.enabled`
- `spring.kafka.bootstrap-servers`
- `axiz.messaging.destinations`
- `axiz.crypto.kms.key-id`
- `axiz.crypto.fields.current-key-id`
- `axiz.crypto.fields.key-ids`

## Deployment

`deploy/helm/payments-history` contains the chart: Deployment with probes, Service, HPA, PDB,
ConfigMaps, ExternalSecret, ServiceAccount, RBAC to read the ConfigMaps and a NetworkPolicy.
The pipelines in `.github/workflows` call the shared axiz workflows (`@v1`):

- `ci.yaml`: pull requests run the shared checks and the build with every quality gate; `main`
  builds the image (Jib, signed with cosign) and deploys it to dev and then qa; a tag `v*` deploys
  to prod after the approval of the `prod` environment.
- Deployments are GitOps: the pipeline records the release (chart commit, image tag and digest) in
  the GitOps repository and Argo CD applies it. `cd.yaml` redeploys or rolls back an existing image.
- `release.yaml`: release-please keeps a release pull request with the next version and the
  changelog; merging it creates the tag.

## Decisions

