# axiz demo: payments

A small payment processor built with the axiz framework: eight services, generated with
`axiz new` and grown with `axiz add` / `axiz generate`, that together use **every capability of
the axiz catalog** (checked by `coverage/`). Business flow: a customer pays a frequent beneficiary
from the web or mobile app; the payment is debited from their account in the core banking system,
sent to the interbank network, notified, audited and reconciled with the daily settlement file.

All names, accounts and data are fictitious. Local credentials are placeholders marked
`local-only`; real environments read secrets from AWS Secrets Manager.

## Services

| Service | Archetype · style · stack | Persistence | What it shows |
|---|---|---|---|
| [payments-experience](services/payments-experience) | experience · bff · reactive | – | Channel API, compositions with optional parts, token relay, Redis cache and idempotency, feature flags, masking, error translation |
| [payments-business](services/payments-business) | business · cqrs · servlet | Aurora | Domain rules, business calendar and cut-off, sequences, field encryption + blind index, transactional outbox, audit to MSK |
| [beneficiaries-crud](services/beneficiaries-crud) | crud · layered · servlet | DynamoDB | Repositories with cursor pagination, soft delete, optimistic locking, cache, idempotency on DynamoDB |
| [core-integration](services/core-integration) | integration · hexagonal · servlet | – | Anti-corruption layer: REST with client credentials, SOAP with WS-Security, API keys from secrets, Redis locks, audit to SQS |
| [payment-process](services/payment-process) | process · hexagonal · servlet | Aurora | Saga with compensations and recovery, Kafka consumer, ShedLock, tenancy |
| [notifications-processor](services/notifications-processor) | event-processor · eda | DynamoDB | Kafka and SQS handlers, inbox, notifications to SNS, audit archive on S3 with DynamoDB locks |
| [settlement-batch](services/settlement-batch) | batch · batch · CronJob | Aurora | Spring Batch over a fixed-width S3 file, control totals, idempotent load, job exit codes |
| [payments-history](services/payments-history) | business · cqrs · reactive | Aurora (R2DBC) | Read model fed by Kafka: reactive command and query buses, reactive transactions on R2DBC, Spring Data R2DBC with auditing, encrypted and money columns, R2DBC inbox |

Contracts live in [`contracts/`](contracts): OpenAPI of every REST service, the AsyncAPI of the
events and the (simulated) external systems in `contracts/external`.

## Architecture

[`docs/architecture.drawio`](docs/architecture.drawio) holds C4 container diagrams, one per flow
(the initiator on the left, arrows to the right):

1. **Payment from the channel**: app → payments-experience → beneficiaries-crud and payments-business.
2. **Payment execution**: payments-business → MSK → payment-process (saga) → core-integration → core banking and network.
3. **Closing, notification and audit**: status changes and audit events reach notifications-processor (SNS, S3).
4. **Daily settlement**: network file on S3 → settlement-batch → Aurora and a summary on S3.
5. **Payment history**: payments-business → MSK → payments-history → Aurora (R2DBC); API consumers read it without blocking a thread.

The saga of payment-process:

| Step | Action | Compensation |
|---|---|---|
| `processing` | order → PROCESSING (payments-business) | order → REJECTED |
| `debit` | debit the customer account (core-integration) | reverse the debit |
| `transfer` | send the transfer (pivot) | – |
| `complete` | order → COMPLETED (never fails the saga; settlement reconciles it) | – |

Every downstream call carries an idempotency key derived from the order and the step, so retries,
redelivered events and sagas resumed after a crash never repeat an effect.

## Run it locally

Requirements: JDK 25, Docker, `jq`; Bruno to explore the APIs. The axiz artifacts must be
reachable: `./mvnw install -DskipTests` in axiz-framework once, or the Nexus mirror in
`~/.m2/settings.xml`. The README at the root of the delivery walks through a clean installation
and every test with `curl`, including what to check in each response.

```bash
scripts/demo.sh up       # infrastructure (local/compose.yaml) + dev secrets + every service
scripts/demo.sh status   # health of every service; repeat until 7/7 services UP
scripts/demo.sh smoke    # pays a beneficiary through the channel API and waits for COMPLETED
scripts/demo.sh down     # stops services, removes containers and volumes
```

| | |
|---|---|
| Channel API | http://localhost:8080 (payments-experience) |
| Services | business 8081 · crud 8082 · core-integration 8083 · process 8084 · notifications 8085 · history 8086 |
| Keycloak | http://localhost:8180 (realm `axiz`, users `ana` / `luis`, passwords `local-only-<user>`) |
| Grafana | http://localhost:3000 (base dashboards *events*, *RED*, *messaging*, *dependencies*, plus *axiz · services* and *axiz · payments*) |
| WireMock | http://localhost:8089 (core banking REST + SOAP, token endpoint, payment network) |

Each service runs with the profiles `local,platform` (`scripts/run.sh <service>`), so it can be
started from the IDE and debugged. The simulated core answers `CB-51` (insufficient funds) for
account `1910099999999` and `CB-62` (blocked) for `1910088888888`; the network rejects CCI
`00219100123456780052` (closed account), so the compensations can be seen end to end.

The settlement job loads the sample file the LocalStack init uploads:
`scripts/run.sh settlement-batch` (the profile `platform` points it to
`in/settlement/NET-SETTLE-SAMPLE.txt`).

### Inside Kubernetes (kind)

```bash
kind/up.sh               # kind cluster, images with Jib, stand-ins (kind/deps), every Helm chart
kind/up.sh down
```

The services are deployed with their own charts and `kind/values/<service>.yaml`; secrets are
created from the dev-secret files (the charts mount `<service>-secrets` whether the External
Secrets Operator creates it or not). The nightly `e2e` workflow runs exactly this plus the smoke
test.

### Bruno

[`bruno/`](bruno) has a request collection with tests for every service: authentication, the
channel flow, M2M calls to business, process and core-integration, and error cases
(see its README for the two secret variables).

## Environments

- `services/<service>/deploy/helm/<service>/values-<env>.yaml`: environment values (no secrets).
- `deploy/platform/axiz-common.yaml`: configuration shared by every service of a namespace
  (zone and business calendar).
- Secrets: AWS Secrets Manager → External Secrets Operator → files in `/var/run/secrets/axiz`.

## Quality

```bash
./mvnw verify            # every service (tests, linters, architecture rules) + capability coverage
```

- `coverage/`: fails when a capability of the axiz catalog is no longer used by the demo, or when
  the evidence listed in `coverage.yaml` disappears from the code.
- `.github/workflows/ci.yaml`: each service through the shared `service-ci` pipeline (build,
  Spotless, Checkstyle, Error Prone + NullAway, SpotBugs, tests, OpenAPI lint and breaking
  changes, Helm validation, image, SBOM); `e2e.yaml`: the whole demo in kind every night.
