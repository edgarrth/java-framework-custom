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
axiz contract sync [--type openapi|asyncapi] [--allow-breaking]         new operations, proposals for existing controllers,
                                              compatibility against the last synced contract
axiz contract lint [--type openapi|asyncapi] [file] | diff [--type openapi|asyncapi] <old> <new>
axiz upgrade [--to <version>] [--dry-run]     parent + manifest + OpenRewrite recipes
axiz doctor                                   tools, versions, coherence, contract, secrets, anti-patterns
axiz local up | down [-v]                     docker compose of the service
axiz catalog [archetypes|capabilities]
```

Offline by default (templates embedded in the binary). `--server https://initializr...` generates
through the initializr with the user's token from `AXIZ_TOKEN` (never stored by the CLI).

Build: `mvn -pl axiz-cli package` (JVM jar `axiz-cli-*-jar-with-dependencies.jar`) or
`mvn -pl axiz-cli -Pnative package` (binary `target/axiz`; needs GraalVM 25).

## Upgrades

`axiz upgrade` stages updates of current v2 projects in a private temporary copy. It updates
Axiz parents, preserves application versions and unrelated parents, validates with doctor and
applies changes atomically. `--dry-run` retains a patch without changing the project. Manifests
must use schema 2; old selectors and unpublished draft schemas are not migrated.

Current workflow bindings can be renamed safely without discarding custom jobs. Identity ledgers,
chart paths and stateful identities protect future renames of v2 services. Java/Spring/JSpecify
conventions and runtime adapter scope repair remain available; pre-v2 Axiz migration recipes and
old workflow fixtures have been removed.

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

Tags `v*` build the CLI JVM artifact, signed native components for all three supported operating
systems, the recipes and the Initializr image. `ci.yaml` does not publish a release or deploy an
environment. A manual build may request native components with `native-cli` before creating a tag.
The recipes version must match the framework version it upgrades to.

`deploy.yaml` independently promotes the verified Maven product and publishes the prepared draft
release, including CLI archives and SHA-256 aliases. `deploy-initializr.yaml` independently
promotes the Initializr image and chart through GitOps. Both require the completed producer run,
attempt, external manifest hash, protected environment and predecessor; Initializr additionally
requires a full values commit. The two products have distinct manifest hashes, even in one CI run.

Read-only Nexus credentials are `NEXUS_READ_USERNAME/PASSWORD`; registry build credentials are
`REGISTRY_BUILD_USERNAME/PASSWORD`. Deployment uses separate `NEXUS_PUBLISH_USERNAME/PASSWORD`
or registry-read/GitOps credentials. Configure `AXIZ_JVM_BASE_IMAGE` and `AXIZ_NATIVE_BASE_IMAGE`
as digest-pinned repository variables. Optional framework candidates are passed to the JVM,
native and Initializr builders through the same repository/run/attempt/hash inputs.

The complete contract and current limitations are in
[the pipeline documentation](../axiz-workflows/docs/pipelines-v2.md).

`deploy-initializr.yaml` también recibe `workflow_run` del CI y resuelve un comprobante firmado
específico de `axiz-initializr`. El modo automático promueve a dev desde el HEAD protegido de main;
el manual conserva los inputs externos. Configure `GITOPS_READ_TOKEN` con lectura del repositorio
GitOps para observar el predecesor; las credenciales de escritura siguen en el deploy protegido.
