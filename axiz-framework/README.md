# axiz-framework

Corporate backend framework for Java 25 microservices on Spring Boot 4.1, Kubernetes (EKS) and AWS.
Root package `pe.axiz`, groupId `pe.axiz`, single version `${revision}` for the whole repository.

## Layers

| Layer | Folder | Artifacts | Rule |
|---|---|---|---|
| Platform | `platform/` | `axiz-parent-*`, `axiz-dependencies`, `axiz-bom` | Versions and build gates |
| 1. Core (Spring-independent) | `core/` | `axiz-core-<capability>[-<technology>]` | Only JDK, SLF4J, JSpecify, Micrometer; technology libraries only in adapters |
| 2. Spring | `spring/` | `axiz-<capability>-spring-boot-autoconfigure` + `-starter` | Auto-configuration and Spring adapters; no dependencies on other capability starters |
| 3. Frameworks | `frameworks/` | `axiz-framework-<style>-api` + `-spring-boot-starter` | One architectural style per service |
| Tools | `codegen/` | `axiz-codegen`, templates, archetypes | Shared by `axiz-initializr` and `axiz-cli` |
| Quality | `quality/` | `axiz-code-standards`, `axiz-archunit-rules`, architecture tests | Machine-checked rules |

## Build

```bash
./mvnw install -DskipTests         # installs BOM, parents and starters in ~/.m2 (what services need)
./mvnw verify                      # build, unit tests, enforcer base rules
./mvnw -Pquality verify            # CI gate: + Spotless check, Checkstyle, Error Prone + NullAway, dependency
                                   # convergence, SpotBugs/FindSecBugs, JaCoCo thresholds, PIT on core
./mvnw -Prelease deploy -Daxiz.nexus.url=https://nexus.example   # sources, javadoc, CycloneDX, Nexus
./mvnw spotless:apply              # format (google-java-format)
```

Requirements: JDK 25, Maven 3.9.x (wrapper included). Maven settings with the Nexus mirror and
credentials come from the CI secrets or `~/.m2/settings.xml`, never from this repository.

## Core capabilities (layer 1)

`commons` (Result, request/page metadata, header catalog, JSON reader/writer, JDBC helpers),
`errors` (types, categories with HTTP status, RFC 9457, catalog), `i18n`, `domain`, `money`,
`time` (business calendar, cut-off, due dates), `identifiers` (DNI, RUC, CE, CCI, card, IBAN,
E.164, email; pluggable SPI), `id` (UUIDv7, ULID), `sensitive` (masking), `logging`,
`observability` (+ `-awssdk`), `metrics` (generic event metrics), `audit` (+ `-kafka`, `-sqs`),
`security` (+ `-redis`), `crypto` (+ `-kms`, `-jose`), `secrets` (+ `-aws`), `tenancy`,
`resilience`, `http`, `soap`, `messaging` (+ `-kafka`, `-sqs`, `-sns`), `outbox` (+ `-r2dbc`),
`inbox` (+ `-r2dbc`, `-dynamodb`), `idempotency` (+ `-redis`, `-dynamodb`), `persistence-jpa`,
`persistence-r2dbc` (Aurora for reactive services), `persistence-dynamodb`, `cache` (+ `-redis`), `sequence` (+ `-dynamodb`), `lock` (+ `-redis`,
`-dynamodb`), `storage` (+ `-s3`), `featureflags`, `fileformats`, `notification`, `saga`, `test`.

Each module has its own README with its public types.

## Conventions

- Properties: `axiz.<capability>.*` (kebab-case). Error codes: `AXZ-<CAPABILITY>-<NNNN>`.
- Every package is `@NullMarked` (JSpecify); NullAway checks it at compile time.
- Formatting is owned by Spotless (google-java-format); Checkstyle only checks naming, imports,
  Javadoc of the public API and forbidden APIs.

## Responsibility boundaries

Core contracts and implementations are independent of Spring. Technology adapters such as
persistence-jpa, persistence-r2dbc, crypto-jose and messaging-kafka use their technology APIs;
“pure Java” does not mean every module depends only on the JDK. `core-test` and
`core-store-contracts` are reusable testing support, not runtime capabilities.

Spring adds lifecycle, transaction, security and servlet/reactive adapters. Filters and
interceptors are integration behavior, not a second implementation of the core algorithms.
Autoconfigure modules own that behavior; starters assemble dependencies. The test starter is a
toolkit consumed with test scope.

Framework styles compose capabilities. CQRS deliberately extends the hexagonal API and starter:
one style per service means one entry point, not a prohibition on internal reuse. Servlet and
reactive implementations adapt different execution models.

| Related capabilities | Distinct responsibilities |
|---|---|
| logging / observability / metrics / audit | Diagnostics; observations and tracing; bounded business-event instruments; business audit events |
| idempotency / inbox / outbox / lock | Replay an operation response; deduplicate received messages; publish committed changes; coordinate concurrent owners |
| security / crypto / sensitive / secrets | Authentication and authorization; cryptography; masking; retrieval of key material and credentials |
| messaging / notification | Transport messages; request delivery to a human recipient |
| id / identifiers / sequence | Generate technical IDs; validate domain identifiers; allocate sequential business numbers |
| persistence / storage | Store application records; store files and objects |
| platform / codegen / quality | Govern dependencies and builds; generate service source; enforce architecture and quality |

CLI and Initializr share `axiz-codegen`. Templates and archetype metadata are generation assets,
not alternative runtime implementations. Demo, documentation and workflows are maintained parts
of the distribution, even though they are not runtime layers.
