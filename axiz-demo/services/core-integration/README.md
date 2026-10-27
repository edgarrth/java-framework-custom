# Core Integration

Anti-corruption layer of the core banking system and the payment network.

| | |
|---|---|
| Archetype | `integration`: Anti-corruption layer in front of systems of record, legacy and third parties |
| Style | `hexagonal` |
| Stack | `servlet` |
| Persistence | `none` |
| axiz | `1.0.0` |
| Base package | `pe.axiz.payments.coreintegration` |

## In the demo

Anti-corruption layer in front of the core banking system and the inter-bank network (both
simulated by WireMock locally; contracts in `../../contracts/external`).

- Debits and reversals through the core REST facade (OAuth2 client credentials), credits through
  its SOAP service (WS-Security digest). Core codes (`CB-51`, `CB-62`...) become
  `PAYMENTS-COREINTEGRATION-*` errors; they never leave this service.
- Debits of the same account are serialized across replicas with a Redis lock.
- Transfers to the network with an API key from the secret store; invalid CCIs are rejected
  before calling it. Every movement is audited to SQS (archived by notifications-processor).

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
`CoreIntegrationApplicationTest` starts the service against real dependencies in containers.
`ContractExamplesTest` checks that every request example of the contract is a valid request.

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
build time into `pe.axiz.payments.coreintegration.api.model` (never versioned). Controllers were generated once
as skeletons that answer `501 Not Implemented` until each operation is implemented.

- `axiz contract sync` adds skeletons for new operations, reports removed ones and checks breaking
  changes against the previous version (openapi-diff).
- Lint the contract with the axiz Spectral ruleset before publishing it.

| Operation | Method | Path |
|---|---|---|
| `debitAccount` | POST | `/v1/debits` |
| `reverseDebit` | POST | `/v1/debits/{movementId}/reversal` |
| `creditAccount` | POST | `/v1/credits` |
| `sendTransfer` | POST | `/v1/transfers` |

## Structure

- `pe.axiz.payments.coreintegration.domain`: Domain model: plain Java, no framework dependencies.
- `pe.axiz.payments.coreintegration.application.usecase`: Use cases: one class per operation, transactional.
- `pe.axiz.payments.coreintegration.application.port.out`: Outbound ports implemented by adapters.
- `pe.axiz.payments.coreintegration.adapter.in.rest`: REST adapter generated from the OpenAPI contract.
- `pe.axiz.payments.coreintegration.adapter.out`: Outbound adapters: persistence, HTTP clients, messaging.
- `pe.axiz.payments.coreintegration.config`: Wiring of use cases, ports and adapters.

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
| `i18n` | `axiz-i18n-spring-boot-starter` | Localized messages and problem details (es-PE by default) from messages*.properties |
| `sensitive` | `axiz-sensitive-spring-boot-starter` | Masking of sensitive fields in responses, logs and audit by sensitivity level |
| `security` | `axiz-security-spring-boot-starter` | OAuth2 resource server (trusted issuers, audiences, claims), caller context, rate limit |
| `resilience` | `axiz-resilience-spring-boot-starter` | Timeouts, retry with backoff, circuit breaker and bulkhead policies by name |
| `http-client` | `axiz-http-client-spring-boot-starter` | Declarative HTTP clients per name with timeouts, retries, circuit breaker, OAuth2 client credentials and header propagation |
| `secrets` | `axiz-secrets-spring-boot-starter` | Secrets from the mounted ExternalSecret directory, environment, Secrets Manager or Parameter Store, cached |
| `soap` | `axiz-soap-spring-boot-starter` | SOAP 1.1/1.2 clients with WS-Security, timeouts and observations |
| `lock` | `axiz-lock-spring-boot-starter` | Distributed locks (in memory only in local/test; shared environments need lock-redis or lock-dynamodb) |
| `lock-redis` | `axiz-lock-spring-boot-starter` | Distributed locks on ElastiCache (Redis/Valkey) |
| `audit` | `axiz-audit-spring-boot-starter` | @Auditable business audit trail, sanitized by sensitivity, published asynchronously |
| `audit-sqs` | `axiz-audit-spring-boot-starter` | Audit events published to SQS |

Add more with `axiz add <capability>`: it adds the starter, its properties, the ConfigMap keys and a
usage example.

## Configuration

- `application.yaml` holds defaults only. Environment values live in the ConfigMaps `axiz-common`,
  `core-integration` and `core-integration-errors`, mounted as files by the chart
  (`deploy/helm/core-integration/values-<env>.yaml`, keys `config` and `errors`).
- Secrets never go to the repository nor to ConfigMaps: they come from AWS Secrets Manager through
  the ExternalSecret and are mounted as files in `/var/run/secrets/axiz`.
- AWS access uses the pod identity (IRSA or EKS Pod Identity); there are no AWS keys anywhere.

Keys each environment must define:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `axiz.http-client.clients`
- `axiz.soap.clients`
- `spring.data.redis.url`
- `axiz.audit.sqs.queue-url`

## Deployment

`deploy/helm/core-integration` contains the chart: Deployment with probes, Service, HPA, PDB,
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

