# axiz-framework

Corporate backend framework for Java 25 microservices on Spring Boot 4.1, Kubernetes (EKS) and AWS.
Root package `pe.axiz`, groupId `pe.axiz`, single version `${revision}` for the whole repository.

## Layers

| Layer | Folder | Artifacts | Rule |
|---|---|---|---|
| Platform | `platform/` | `axiz-parent-*`, `axiz-dependencies`, `axiz-bom` | Versions and build gates |
| 1 · Core (pure Java) | `core/` | `axiz-core-<capability>[-<technology>]` | Only JDK, SLF4J, JSpecify, Micrometer; technology libraries only in adapters |
| 2 · Spring | `spring/` | `axiz-<capability>-spring-boot-autoconfigure` + `-starter` | Wiring only; never depends on another starter |
| 3 · Frameworks | `frameworks/` | `axiz-framework-<style>-api` + `-spring-boot-starter` | One architectural style per service |
| Tools | `codegen/` | `axiz-codegen`, templates, archetypes | Shared by `axiz-initializr` and `axiz-cli` |
| Quality | `quality/` | `axiz-code-standards`, `axiz-archunit-rules`, architecture tests | Machine-checked rules |

## Build

```bash
./mvnw verify                      # build, unit tests, enforcer, Spotless check, Checkstyle, Error Prone + NullAway
./mvnw -Pquality verify            # + SpotBugs/FindSecBugs, JaCoCo thresholds, PIT on core
./mvnw -Prelease deploy -Daxiz.nexus.url=https://nexus.example   # sources, javadoc, CycloneDX, Nexus
./mvnw spotless:apply              # format (google-java-format)
./mvnw -Daxiz.errorprone.skip      # local escape hatch to compile without Error Prone
```

Requirements: JDK 25, Maven 3.9.x (wrapper included). Maven settings with the Nexus mirror and
credentials come from the CI secrets or `~/.m2/settings.xml`, never from this repository.

## Core capabilities (layer 1)

`commons` (Result, request/page metadata, header catalog, JSON reader/writer, JDBC helpers) ·
`errors` (types, categories with HTTP status, RFC 9457, catalog) · `i18n` · `domain` · `money` ·
`time` (business calendar, cut-off, due dates) · `identifiers` (DNI, RUC, CE, CCI, card, IBAN,
E.164, email; pluggable SPI) · `id` (UUIDv7, ULID) · `sensitive` (masking) · `logging` ·
`observability` (+ `-awssdk`) · `metrics` (generic event metrics) · `audit` (+ `-kafka`, `-sqs`) ·
`security` (+ `-redis`) · `crypto` (+ `-kms`, `-jose`) · `secrets` (+ `-aws`) · `tenancy` ·
`resilience` · `http` · `soap` · `messaging` (+ `-kafka`, `-sqs`, `-sns`) · `outbox` (+ `-r2dbc`) ·
`inbox` (+ `-r2dbc`, `-dynamodb`) · `idempotency` (+ `-redis`, `-dynamodb`) · `persistence-jpa` ·
`persistence-r2dbc` (Aurora for reactive services) · `persistence-dynamodb` · `cache` (+ `-redis`) · `sequence` (+ `-dynamodb`) · `lock` (+ `-redis`,
`-dynamodb`) · `storage` (+ `-s3`) · `featureflags` · `fileformats` · `notification` · `saga` · `test`.

Each module has its own README with its public types.

## Conventions

- Properties: `axiz.<capability>.*` (kebab-case). Error codes: `AXZ-<CAPABILITY>-<NNNN>`.
- Every package is `@NullMarked` (JSpecify); NullAway checks it at compile time.
- Formatting is owned by Spotless (google-java-format); Checkstyle only checks naming, imports,
  Javadoc of the public API and forbidden APIs.
