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

`axiz upgrade` stages the migration in a private temporary copy. It presents schema 2 metadata to
OpenRewrite while keeping the previous valid Maven model until the recipes can resolve it. It
updates every declared Axiz parent in the reactor, preserving application versions and unrelated
parents. `doctor` also validates modules of parentless aggregators. XML parsing refuses DTDs,
external entities, symlinked modules and paths outside the reactor.

A dry-run executes the complete migration in that copy and writes `target/rewrite/rewrite.patch`;
it leaves source files untouched. Successful application checks that the original files did not
change concurrently. If applying a later file fails, earlier changes are restored; an unsuccessful
recovery retains its baseline for manual recovery. Build caches, Git metadata and generated Maven
POMs are not copied back.

Standard 1.0.0 pipelines are recognized through semantic comparison with the retained generator
baselines and migrated to independent build/deploy consumers. Custom workflows produce a proposal
and stop application without discarding custom jobs. Configure the new read/build/publish secrets
before running those workflows remotely. Renames preserve the identity ledger, existing chart
path, audiences and DNS aliases; chart metadata follows the new service name.

The OpenRewrite plugin version is filtered from the framework parent into the CLI, so upgrades
of an older project use the curated plugin rather than its previous parent configuration. The
current baseline is the last plugin release available in Maven Central; newer releases moved to
a different artifact repository, which would require a separately configured Nexus proxy.

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
