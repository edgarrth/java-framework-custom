# axiz-cli

The `axiz` command: creates services from the archetypes, adds capabilities, generates artifacts
(use cases, handlers, entities, HTTP clients, migrations), keeps code and OpenAPI contracts in
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
