# Payment Network Gateway

Facade and security for external systems.

| | |
|---|---|
| Archetype | `gateway`: Facade for the external payment network |
| Structure | `hexagonal` |
| Extensions | – |
| Stack | `servlet` |
| Persistence | `none` |
| axiz | `2.0.0` |
| Base package | `pe.axiz.payments.networkgateway` |

## In the demo

`POST /v1/transfers` accepts requests from payment-process with `payments:process` authority.
The use case validates the CCI and calls the payment network through its own outbound port.
The API key is read from mounted secret files. Redis stores idempotent responses, the caller's
key is forwarded to the network, and the transfer is audited to SQS. The platform profile uses
port 8087. Debits, credits and reversals belong to banking-core.

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
`PaymentNetworkGatewayApplicationTest` starts the service against real dependencies in containers.
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
build time into `pe.axiz.payments.networkgateway.api.model` (never versioned). Controllers were generated once
as skeletons that answer `501 Not Implemented` until each operation is implemented.

- `axiz contract sync` adds skeletons for new operations, reports removed ones and checks breaking
  changes against the previous version (openapi-diff).
- Lint the contract with the axiz Spectral ruleset before publishing it.

| Operation | Method | Path |
|---|---|---|
| `sendTransfer` | POST | `/v1/transfers` |


## Structure

- `pe.axiz.payments.networkgateway.domain`: Domain model: plain Java, no structure dependencies.
- `pe.axiz.payments.networkgateway.application.usecase`: Use cases: one class per operation, transactional.
- `pe.axiz.payments.networkgateway.application.model`: Owned application contracts independent of HTTP models.
- `pe.axiz.payments.networkgateway.application.port.out`: Outbound ports implemented by adapters.
- `pe.axiz.payments.networkgateway.adapter.in.rest`: REST adapter generated from the OpenAPI contract.
- `pe.axiz.payments.networkgateway.adapter.out`: Outbound adapters: persistence, HTTP clients, messaging.
- `pe.axiz.payments.networkgateway.config`: Wiring of use cases, ports and adapters.

## Capabilities

| Capability | Starter | What it brings |
|---|---|---|
| `foundation` | `axiz-foundation-spring-boot-starter` | Clock and business calendar (zone, weekends, holidays), JSON and validation defaults |
| `errors` | `axiz-errors-spring-boot-starter` | RFC 9457 problem details, error catalog (ConfigMap <app>-errors), external error mapping |
| `logging` | `axiz-logging-spring-boot-starter` | Structured JSON logs with trace and request context, HTTP access log with masking |
| `observability` | `axiz-observability-spring-boot-starter` | OpenTelemetry traces and metrics through Micrometer (OTLP), no Java agent |
| `metrics` | `axiz-metrics-spring-boot-starter` | Generic event metrics (count, duration, amounts by currency, values) with bounded tags |
| `config` | `axiz-config-spring-boot-starter` | ConfigMaps mounted as files (axiz-common, <app>, <app>-errors; no Kubernetes API access), Parameter Store, required keys check |
| `sensitive` | `axiz-sensitive-spring-boot-starter` | Masking of sensitive fields in responses, logs and audit by sensitivity level |
| `web` | `axiz-web-spring-boot-starter` | Request metadata headers, correlation, pagination, API versioning, CORS and JSON conventions |
| `security` | `axiz-security-spring-boot-starter` | OAuth2 resource server (trusted issuers, audiences, claims), caller context, rate limit |
| `resilience` | `axiz-resilience-spring-boot-starter` | Timeouts, retry with backoff, circuit breaker and bulkhead policies by name |
| `http-client` | `axiz-http-client-spring-boot-starter` | Declarative HTTP clients per name with timeouts, retries, circuit breaker, OAuth2 client credentials and header propagation |
| `secrets` | `axiz-secrets-spring-boot-starter` | Secrets from the mounted ExternalSecret directory, environment, Secrets Manager or Parameter Store, cached |
| `audit` | `axiz-audit-spring-boot-starter` | @Auditable business audit trail, sanitized by sensitivity, published asynchronously |
| `idempotency` | `axiz-idempotency-spring-boot-starter` | Idempotency-Key for POST/PATCH with stored responses (in memory only in local/test; shared environments need idempotency-redis or idempotency-dynamodb) |
| `idempotency-redis` | `axiz-idempotency-spring-boot-starter` | Idempotency store on ElastiCache (Redis/Valkey) |
| `audit-sqs` | `axiz-audit-spring-boot-starter` | Audit events published to SQS |

Add more with `axiz add <capability>`: it adds the starter, its properties, the ConfigMap keys and a
usage example.

## Configuration

- `application.yaml` holds defaults only. Environment values live in the ConfigMaps `axiz-common`,
  `payment-network-gateway` and `payment-network-gateway-errors`, mounted as files by the chart
  (`deploy/helm/payment-network-gateway/values-<env>.yaml`, keys `config` and `errors`).
- Secrets never go to the repository nor to ConfigMaps: they come from AWS Secrets Manager through
  the ExternalSecret and are mounted as files in `/var/run/secrets/axiz`.
- AWS access uses the pod identity (IRSA or EKS Pod Identity); there are no AWS keys anywhere.

Keys each environment must define:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `axiz.http-client.clients`
- `spring.data.redis.url`
- `axiz.audit.sqs.queue-url`

## Deployment

`deploy/helm/payment-network-gateway` contains the chart: Deployment with probes, Service, HPA, PDB,
ConfigMaps, ExternalSecret, ServiceAccount, RBAC to read the ConfigMaps and a NetworkPolicy.
The workflows use the immutable Axiz 2.0.0 contracts:

- `ci.yaml` builds and validates the product. It records the source commit, final version,
  JVM/native variant, platforms and pinned base image in a signed build manifest. Set
  `AXIZ_JVM_BASE_IMAGE` and `AXIZ_NATIVE_BASE_IMAGE` to registry references with SHA-256 digests;
  a manual build can supply a specific `base-image`. Optional framework candidate inputs bind
  the dependency to its producer run, attempt and signed manifest hash.
- `deploy.yaml` also listens for completed `ci` runs and promotes a verified service build to dev
  through a signed producer proof. It requires the source to be the current HEAD of protected
  main and a repository secret `GITOPS_READ_TOKEN` with read-only access to GitOps. The resolver
  reads the predecessor without using the GitHub App's write credential; the independent deploy
  checks that predecessor again before writing. CI runs without a product proof do not deploy.
- `deploy.yaml` promotes or rolls back a completed successful build. Supply its run id, attempt,
  manifest SHA-256, environment, full values commit and previous deployed manifest hash
  (`initial-deployment` for the first deployment). It reuses image/chart digests, updates GitOps
  and waits for Argo CD; deployment never rebuilds. Protected environments provide approval.
- Build credentials are `NEXUS_READ_USERNAME/PASSWORD`, `BUILD_REGISTRY` and
  `REGISTRY_BUILD_USERNAME/PASSWORD`. Deploy uses registry read credentials plus the GitOps App
  and Argo read token. Configure `GITOPS_REPOSITORY` and `ARGOCD_SERVER` as repository variables.
- `release.yaml`: release-please keeps a release pull request with the next version and the
  changelog; merging it creates the tag. `version.txt` and `.release-please-manifest.json` hold
  the last released version (`0.0.0`: nothing released yet); the pom holds the next one
  (`0.1.0-SNAPSHOT`). The first release is `0.1.0` (`initial-version` in
  `release-please-config.json`); later ones follow Conventional Commits.

## Decisions

