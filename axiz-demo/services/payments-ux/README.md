# Payments UX

Payments API for the channels.

| | |
|---|---|
| Archetype | `ux`: Channel API that composes downstream services |
| Structure | `bff` |
| Extensions | None |
| Stack | `reactive` |
| Persistence | `none` |
| axiz | `2.0.0` |
| Base package | `pe.axiz.payments.ux` |

## In the demo

Channel API of the web and mobile apps (BFF, reactive). The customer is always the token subject.

- `POST /v1/payments`: reads the beneficiary fresh from beneficiaries (no cache), checks the
  currency and the `payments-usd` feature flag, and registers the order in payment-orders with
  the caller's `Idempotency-Key` (Redis store: a retried request replays the first response).
- `GET /v1/payments/{id}`: order (required) + beneficiary alias (optional part of the composition:
  the screen still works when beneficiaries is slow or down). Another customer's payment is
  reported as not found.
- `GET /v1/beneficiaries`: cached pages (`customer-beneficiaries`, 30 s, L1 + Redis), holder names
  and accounts masked for the channel.
- Downstream business errors are translated to channel errors (`PAYMENTS-PAYMENTSUX-*`).
- HTTP clients generated with `axiz generate http-client` from the contracts in `../../contracts`.

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

`ArchitectureTest` enforces the rules of the `bff` style and the general axiz rules;
`PaymentsUxApplicationTest` starts the service against real dependencies in containers.
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
build time into `pe.axiz.payments.ux.api.model` (never versioned). Controllers were generated once
as skeletons that answer `501 Not Implemented` until each operation is implemented.

- `axiz contract sync` adds skeletons for new operations, reports removed ones and checks breaking
  changes against the previous version (openapi-diff).
- Lint the contract with the axiz Spectral ruleset before publishing it.

| Operation | Method | Path |
|---|---|---|
| `createPayment` | POST | `/v1/payments` |
| `getPayment` | GET | `/v1/payments/{paymentId}` |
| `listMyBeneficiaries` | GET | `/v1/beneficiaries` |

## Structure

- `pe.axiz.payments.ux.controller`: Channel API generated from the OpenAPI contract.
- `pe.axiz.payments.ux.composition`: Compositions of downstream calls, one per API tag.
- `pe.axiz.payments.ux.client`: HTTP clients of downstream services.

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
| `resilience` | `axiz-resilience-spring-boot-starter` | Timeouts, retry with backoff, circuit breaker and bulkhead policies by name |
| `http-client` | `axiz-http-client-spring-boot-starter` | Declarative HTTP clients per name with timeouts, retries, circuit breaker, OAuth2 client credentials and header propagation |
| `cache` | `axiz-cache-spring-boot-starter` | @AxizCacheable with Mono/Flux support, L1 Caffeine |
| `idempotency` | `axiz-idempotency-spring-boot-starter` | Idempotency-Key for POST/PATCH with stored responses (in memory only in local/test; shared environments need idempotency-redis or idempotency-dynamodb) |
| `idempotency-redis` | `axiz-idempotency-spring-boot-starter` | Idempotency store on ElastiCache (Redis/Valkey) |
| `cache-redis` | `axiz-cache-spring-boot-starter` | L2 cache on ElastiCache (Redis/Valkey), tolerant to Redis outages |
| `featureflags` | `axiz-featureflags-spring-boot-starter` | Feature flags (OpenFeature) by percentage, channel, tenant and rules, from the ConfigMap |

Add more with `axiz add <capability>`: it adds the starter, its properties, the ConfigMap keys and a
usage example.

## Configuration

- `application.yaml` holds defaults only. Environment values live in the ConfigMaps `axiz-common`,
  `payments-ux` and `payments-ux-errors`, mounted as files by the chart
  (`deploy/helm/payments-ux/values-<env>.yaml`, keys `config` and `errors`).
- Secrets never go to the repository nor to ConfigMaps: they come from AWS Secrets Manager through
  the ExternalSecret and are mounted as files in `/var/run/secrets/axiz`.
- AWS access uses the pod identity (IRSA or EKS Pod Identity); there are no AWS keys anywhere.

Keys each environment must define:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `axiz.http-client.clients`
- `spring.data.redis.url`
- `axiz.feature-flags.flags`

## Deployment

`deploy/helm/payments-ux` contains the chart: Deployment with probes, Service, HPA, PDB,
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
