# axiz-framework

Corporate backend framework for Java 25 microservices on Spring Boot 4.1, Kubernetes (EKS) and AWS.
Root package `pe.axiz`, groupId `pe.axiz`, single version `${revision}` for the whole repository.

## Layers

| Layer | Folder | Artifacts | Rule |
|---|---|---|---|
| Platform | `platform/` | `axiz-parent-*`, `axiz-dependencies`, `axiz-bom` | Versions and build gates |
| 1. Core | `core/` | `axiz-core-<capability>` | JDK, SLF4J, JSpecify, Micrometer; no technology or runtime dependencies |
| 2. Adapters | `adapters/` | `axiz-adapter-<capability>-<technology>` | Implement core ports using native technology clients |
| 2. Persistence kits | `kits/` | `axiz-kit-jpa`, `axiz-kit-r2dbc`, `axiz-kit-dynamodb` | Persistence types; used only by service repositories and persistence adapters |
| 3. Spring | `spring/` | `axiz-<capability>-spring-boot-autoconfigure` and `-starter` | Lifecycle, beans, transactions and facades; capabilities communicate through core ports |
| 4. Structures | `structures/` | `axiz-structure-<structure>-api` and `-spring-boot-starter` | Exactly one structure per service |
| 4. Extensions | `extensions/` | `axiz-extension-cqrs-api` and `-spring-boot-starter` | Optional CQRS on hexagonal business/event-processor services |
| Generation | `codegen/` | `axiz-codegen`, templates, archetypes | Shared by CLI and Initializr |
| Quality | `quality/` | Standards, ArchUnit, test support and store contracts | Tests and architecture enforcement |

[implementation and validation status](docs/migration/2.0/IMPLEMENTACION.md) before accepting a
candidate. Source changes do not imply that 2.0 has been published.

## Build

```bash
./mvnw --bootstrap install         # fresh source: independent BOMs, then parents and modules in ~/.m2
./mvnw verify                      # build, unit tests, enforcer base rules
./mvnw -Pquality verify            # CI gate: + Spotless check, Checkstyle, Error Prone + NullAway, dependency
                                   # convergence, SpotBugs/FindSecBugs, JaCoCo thresholds, PIT on core
./mvnw -Pquality,release deploy -DaltDeploymentRepository=staging::file:///tmp/axiz-staging  # local candidate staging
./mvnw -Pquality -pl '!:axiz-dependencies,!:axiz-bom' spotless:apply  # format
```

Requirements: JDK 25, Maven 3.9.x (wrapper included). Maven settings with the Nexus mirror and
credentials come from the CI secrets or `~/.m2/settings.xml`, never from this repository.

## Core capabilities (layer 1)

`commons`, `errors`, `i18n`, `domain`, `money`, `time`, `identifiers`, `id`, `sensitive`,
`logging`, `observability`, `metrics`, `audit`, `security`, `crypto`, `secrets`, `tenancy`,
`resilience`, `http`, `soap`, `messaging`, `outbox`, `inbox`, `idempotency`, `cache`,
`sequence`, `lock`, `storage`, `featureflags`, `fileformats`, `notification` and `saga`.

Redis, AWS, Kafka and persistence implementations live in layer 2. Test support and shared store
contracts live in `quality`; they are not production core capabilities.

Each module has its own README with its public types.

## Conventions

- Properties: `axiz.<capability>.*` (kebab-case). Error codes: `AXZ-<CAPABILITY>-<NNNN>`.
- Every package is `@NullMarked` (JSpecify); NullAway checks it at compile time.
- Formatting is owned by Spotless (google-java-format); Checkstyle only checks naming, imports,
  Javadoc of the public API and forbidden APIs.

## Responsibility boundaries

Core contracts and algorithms are independent of technology and runtime frameworks.
Native asynchronous ports use `CompletionStage` or `Flow.Publisher`. Technology adapters
implement these ports; persistence kits expose their technology types deliberately.

Only `axiz-kit-r2dbc`, `axiz-adapter-inbox-r2dbc` and `axiz-adapter-outbox-r2dbc` expose Reactor
as native technology contracts, preserving transaction context. AWS adapters may use Reactive
Streams internally for SDK interoperability without exposing it through their public API.

Spring adds lifecycle, transactions, security and servlet/reactive facades. Filters and
interceptors integrate core algorithms with the runtime. Starters assemble dependencies;
services consume the test starter with test scope.

A service chooses one archetype, one execution stack, one structure and optional extensions.
CQRS is an extension of hexagonal; event handlers, inbox and outbox are capabilities independent
of EDA. Service adapters are runtime dependencies; kits belong in persistence packages. The
domain may depend on Axiz domain contracts while remaining independent of third-party frameworks.

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
