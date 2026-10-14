# Beneficiaries Crud

Frequent beneficiaries of each customer.

| | |
|---|---|
| Archetype | `crud`: Simple maintenance tables and catalogs |
| Style | `layered` |
| Stack | `servlet` |
| Persistence | `dynamodb` |
| axiz | `0.1.0-SNAPSHOT` |
| Base package | `pe.axiz.payments.beneficiaries` |

## In the demo

Frequent beneficiaries of each customer (layered style on DynamoDB).

- One active beneficiary per account and customer, at most `beneficiaries.max-per-customer`.
- Logical removal (`deletedAt`): items are kept for audit and skipped by queries.
- Cursor pagination over the DynamoDB partition; a cursor of another customer is rejected.
- Single items cached (`beneficiaries`, 5 min) and evicted on every change; registration is
  idempotent with the `Idempotency-Key` (DynamoDB store, shared by replicas).

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

`ArchitectureTest` enforces the rules of the `layered` style and the general axiz rules;
`BeneficiariesCrudApplicationTest` starts the service against real dependencies in containers.
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
build time into `pe.axiz.payments.beneficiaries.api.model` (never versioned). Controllers were generated once
as skeletons that answer `501 Not Implemented` until each operation is implemented.

- `axiz contract sync` adds skeletons for new operations, reports removed ones and checks breaking
  changes against the previous version (openapi-diff).
- Lint the contract with the axiz Spectral ruleset before publishing it.

| Operation | Method | Path |
|---|---|---|
| `listBeneficiaries` | GET | `/v1/customers/{customerId}/beneficiaries` |
| `createBeneficiary` | POST | `/v1/customers/{customerId}/beneficiaries` |
| `getBeneficiary` | GET | `/v1/customers/{customerId}/beneficiaries/{beneficiaryId}` |
| `updateBeneficiary` | PUT | `/v1/customers/{customerId}/beneficiaries/{beneficiaryId}` |
| `deleteBeneficiary` | DELETE | `/v1/customers/{customerId}/beneficiaries/{beneficiaryId}` |

## Structure

- `pe.axiz.payments.beneficiaries.controller`: REST controllers generated from the OpenAPI contract.
- `pe.axiz.payments.beneficiaries.service`: Business services, transactional.
- `pe.axiz.payments.beneficiaries.repository`: Data access.
- `pe.axiz.payments.beneficiaries.model`: Entities and items.

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
| `dynamodb` | `axiz-dynamodb-spring-boot-starter` | DynamoDB enhanced client, repositories with cursor pagination and optimistic locking |
| `cache` | `axiz-cache-spring-boot-starter` | @AxizCacheable with Mono/Flux support, L1 Caffeine |
| `idempotency` | `axiz-idempotency-spring-boot-starter` | Idempotency-Key for POST/PATCH with stored responses (in memory only in local/test; shared environments need idempotency-redis or idempotency-dynamodb) |
| `idempotency-dynamodb` | `axiz-idempotency-spring-boot-starter` | Idempotency store on DynamoDB (conditional writes and TTL) |

Add more with `axiz add <capability>`: it adds the starter, its properties, the ConfigMap keys and a
usage example.

## Configuration

- `application.yaml` holds defaults only. Environment values live in the ConfigMaps `axiz-common`,
  `beneficiaries-crud` and `beneficiaries-crud-errors`, read by Spring Cloud Kubernetes
  (`deploy/helm/beneficiaries-crud/values-<env>.yaml`, keys `config` and `errors`).
- Secrets never go to the repository nor to ConfigMaps: they come from AWS Secrets Manager through
  the ExternalSecret and are mounted as files in `/var/run/secrets/axiz`.
- AWS access uses the pod identity (IRSA or EKS Pod Identity); there are no AWS keys anywhere.

Keys each environment must define:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `axiz.dynamodb.table-prefix`

## Deployment

`deploy/helm/beneficiaries-crud` contains the chart: Deployment with probes, Service, HPA, PDB,
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

