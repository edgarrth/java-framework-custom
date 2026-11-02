# axiz-cli

The `axiz` command: creates services from the archetypes, adds capabilities, generates artifacts
(use cases, handlers, entities, HTTP clients, migrations), keeps code and OpenAPI/AsyncAPI contracts in
sync, upgrades services between framework versions and checks a project (`axiz doctor`).

It embeds the generation engine of the framework (`pe.axiz:axiz-codegen`), so it works offline and
produces exactly what the initializr and CI produce. Generated sources come out formatted as `spotless:check`
expects: the jar ships google-java-format and exports the `jdk.compiler` packages it needs in its
manifest (`Add-Exports`); the native binary, where the formatter cannot run, runs the project's
`./mvnw -q spotless:apply` instead.

```bash
mvn -pl axiz-cli -am package             # target/axiz-cli-<version>-jar-with-dependencies.jar
java -jar axiz-cli/target/axiz-cli-*-jar-with-dependencies.jar catalog
mvn -pl axiz-cli -am -Pnative package    # native binary target/axiz (GraalVM 25)
```

Commands and options: see the README of `axiz-tools` and `axiz --help`.

## Maven aggregators

Run project commands with `--dir` pointing at the directory containing `.axiz/project.yaml`
and the root POM. The CLI follows declared Maven modules and selects the application containing
the base `application.yaml`, `application.yml` or `application.properties`. With multiple
applications, the manifest's contract paths must identify exactly one of them; an ambiguous
selection fails before writing files. Module paths cannot escape the project or follow symlinks.

Generation, contract synchronization, capability dependencies and Flyway migrations use that
application module. Snapshots and the identity ledger stay beside the root manifest. Reactive
checks require BlockHound in the application tests, without requiring it in a sibling Java library.
An upgrade updates all declared Axiz parents; `--rename` changes only the application's artifactId
and deployment identity. It preserves the aggregator, sibling artifacts, Java packages and stateful
identities. Standard generated workflows migrate to separate build/deploy consumers using the
application module, its source POM and the existing owned chart directory. Customized legacy
workflows remain a patch for review.
Explicitly named reactor POMs can be read by the editors, but the standard service build
requires `<module>/pom.xml`; upgrading that workflow with another POM filename also produces
a proposal and preserves the original project.

## AsyncAPI contracts

Use `axiz new ... --asyncapi events.yaml` to generate Java payload records, typed inbound
handler skeletons, separate Spring AOT binding metadata and the exact `receive` subscriptions. AsyncAPI 3 operations must reference local channels and messages; `message.name`
is the versioned CloudEvents type (`pe.axiz.orders.created.v1`). External/cyclic references and
unflattened traits are rejected. Payload records contain JDK types and are placed in `domain.event`
for hexagonal services or `event` for the other structures.

`axiz contract sync --type asyncapi` adds payloads and proposes edits as `.axiz-new`, preserving
existing code. Optional fields may extend the published type; incompatible changes require a new
`.vN` type in parallel. `--allow-breaking` cannot override this rule. Use `contract lint --type
asyncapi` for local structural checks and `contract diff --type asyncapi old.yaml new.yaml` for
compatibility. Corporate Spectral lint still runs in CI. With only an AsyncAPI contract, `sync`
and `lint` select it automatically.

`axiz generate event order-created-v1` reads the project's AsyncAPI payload; it never replaces an
existing record without `--force`. `doctor` compares receive types with subscriptions in the base
configuration and any explicit profile overrides (YAML or properties), and rejects a stale
snapshot. The handler skeleton rejects messages until its business action is implemented, so implement it
before consuming live messages. Runtime subscription verification continues to reject missing
handlers. Schema comparison cannot establish that an unchanged field
retains its business meaning; review that as part of any contract change.

`axiz generate event-handler <name-vN>` (or `handler`) uses the contract's received type and its
payload. Reactive services get `ReactiveTypedEventHandler` with a `Publisher<Void>` method;
servlet services get `TypedEventHandler`. It rejects a producer-only type and preserves existing
implementations. Spring native binding metadata stays in `config`, outside the payload layer.

Reactive test plans opt in with `@ExtendWith(BlockHoundExtension.class)`. The framework's JUnit
listener installs detection before schedulers start; the unrelated JUnit extension autodetection
flag is not required. `doctor` recognizes the actual annotation and excludes comments/literals
when inspecting it.
