# axiz-tools

Developer tooling of the axiz platform. Every tool uses the generation engine of the framework
(`pe.axiz:axiz-codegen`), so the web generator, the CLI and CI produce exactly the same code.

| Module | What it is |
|---|---|
| `axiz-cli` | The `axiz` command (picocli, native binary with GraalVM) |
| `axiz-initializr` | Web generator and REST API behind the corporate SSO (also the CLI's online mode) |
| `axiz-rewrite-recipes` | OpenRewrite recipes that `axiz upgrade` applies between versions |

## axiz CLI

```text
axiz new <name> --archetype <a> [--framework] [--stack] [--persistence] [-c <capability>]... --openapi <file>
axiz new -i                                   interactive
axiz add <capability>...                      starter, properties, ConfigMap keys, tables, example
axiz generate <type> <name>                   usecase, command, query, handler, event, event-handler,
                                              entity, repository, controller, dto, mapper,
                                              http-client (--contract), migration, error-code, test
axiz contract sync [--allow-breaking]         new operations, proposals for existing controllers,
                                              compatibility against the last synced contract
axiz contract lint [file] | diff <old> <new>
axiz upgrade [--to <version>] [--dry-run]     parent + manifest + OpenRewrite recipes
axiz doctor                                   tools, versions, coherence, contract, secrets, anti-patterns
axiz local up | down [-v]                     docker compose of the service
axiz catalog [archetypes|capabilities]
```

Offline by default (templates embedded in the binary). `--server https://initializr...` generates
through the initializr with the user's token from `AXIZ_TOKEN` (never stored by the CLI).

Build: `mvn -pl axiz-cli package` (JVM jar `axiz-cli-*-jar-with-dependencies.jar`) or
`mvn -pl axiz-cli -Pnative package` (binary `target/axiz`; needs GraalVM 25).

## axiz initializr

- Web UI at `/` and API under `/v1` (contract in `axiz-initializr/docs/openapi.yaml`):
  `GET /v1/metadata`, `POST /v1/projects` (ZIP), `POST /v1/projects/validate`,
  `POST /v1/contracts/lint`, `POST /v1/projects/github`.
- Security: OIDC login for the UI (SPA CSRF cookie, strict CSP), bearer JWT for the CLI and
  automations, health probes only anonymous. GitHub repositories are created with the user's own
  OAuth authorization; tokens are never stored.
- Generation happens in memory (no temporary directories, no paths built from user input), with
  size limits on contracts and archives; every generation is audited and counted
  (`axiz.initializr.generations` by archetype, style, stack and outcome).
- Deployed with its own chart (`axiz-initializr/deploy/helm`), configuration in ConfigMaps and
  client secrets from the ExternalSecret.

## Releases

Tags `v*` publish the CLI jar and native binaries, the recipes and the initializr image; the
recipes version must match the framework version it upgrades to.
