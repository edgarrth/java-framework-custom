# axiz-workflows

Reusable GitHub Actions workflows, composite actions, Renovate presets and GitOps references of the
axiz platform. Repositories call them with `secrets: inherit` and pin an immutable release
(`@v1.0.0`); the Renovate preset (`helpers:pinGitHubActionDigestsToSemver`) turns that reference
into the commit SHA of the release and proposes each new one as a pull request, so a moved tag can
never change the pipeline of a service. Inside this repository the references between workflows
and actions carry the release version too (`# x-release-please-version`: release-please updates
them in the release pull request) and `self-check` rejects any other reference. Third-party actions
are pinned by commit SHA (checked by `self-check`, kept up to date by Renovate). Protect the `v*`
tags with a ruleset (no update, no deletion) and enable immutable releases in the repository.

| Workflow | Used by | What it does |
|---|---|---|
| `pr.yaml` | every repository (pull requests) | Conventional Commits title, gitleaks on the PR commits, dependency review (licenses, vulnerabilities), Semgrep, Trivy filesystem scan (SARIF to code scanning), actionlint |
| `lib-publish.yaml` | framework, libraries, starters, tools | `verify -Pquality` (Spotless, Checkstyle, Error Prone + NullAway, SpotBugs/FindSecBugs, ArchUnit, JaCoCo, PIT), SonarQube, SBOM and its vulnerability scan; with `stage` keeps the artifacts that passed (staging repository + SHA-256 manifest); `publish` promotes them to Nexus from `develop` (SNAPSHOT) and `v*` tags (release) |
| `lib-promote.yaml` | framework, tools | promotes the staged artifacts of the same run to Nexus, verified against their manifest; called from a job that `needs` every gate, so a failing gate never publishes |
| `service-ci.yaml` | services | The same gates plus OpenAPI/AsyncAPI lint (Spectral, axiz ruleset), breaking changes (oasdiff), chart lint, schema (kubeconform) and best practices (kube-linter) per environment; on `main`/tags the Jib image (and the native one when asked), image scan, cosign signature with the key in AWS KMS and SBOM attestation. Outputs the image tag and digest |
| `service-cd.yaml` | services | GitOps deployment: writes the release (chart commit, image tag and digest) to the GitOps repository; Argo CD applies it. Approvals from GitHub environments; optional wait until Argo CD reports it healthy |
| `release.yaml` | every repository | release-please: release PR with version and changelog from Conventional Commits; merging it creates the tag and the GitHub release |
| `native.yaml` | tools | GraalVM binaries for Linux, macOS and Windows, smoke test, attached to the release |
| `native-nightly.yaml` | framework, demo | Nightly native compilation and native tests (demo services, one generated service per archetype) |
| `infra.yaml` | service infrastructure | Terraform fmt, validate, Trivy config scan, plan in the PR, apply after merge with approval |

Composite actions: `actions/setup-maven` (JDK 25 or GraalVM, Nexus as the only repository,
credentials only as environment variables), `actions/gitops-update` (validated write of a
release to the GitOps repository, retried on concurrent pushes) and `actions/test-report` (job
summary with the commit, the JDK, the Maven command and, per module, the unit and integration
tests that passed, failed or were skipped, read from the Surefire and Failsafe reports; it runs
even when the build fails).

## Pipeline of a service

```text
pull request ─ pr ─┐
                   └ service-ci (build, gates, contract, chart)
main ───────────── service-ci → image (signed) → service-cd dev → service-cd qa
tag v* ─────────── service-ci → image (signed) → service-cd prod (environment approval)
manual (cd.yaml) ─ service-cd <env> with an existing image: promotion, redeploy, rollback
```

A deployment only runs after the build and image of the same commit succeeded. The pipeline never
runs `helm` against a cluster.

## GitOps (`gitops/`)

- `environments/<env>/<service>.yaml` in the GitOps repository (written by `service-cd`): service
  repository, chart path and commit, image tag and digest, who deployed and from which run.
- `gitops/argocd/applicationset.yaml`: one Argo CD Application per file; the chart and its
  `values.yaml` + `values-<env>.yaml` come from the service repository at the recorded commit.
- `gitops/argocd/project.yaml`: allowed sources, namespaces (`axiz-dev`, `axiz-qa`, `axiz-prod`)
  and namespaced resource kinds; no cluster-scoped resources.
- `gitops/policies/verify-images.yaml`: Kyverno policy, only images from Nexus signed by the CI key
  run in the service namespaces.

A rollback is a revert in the GitOps repository (or `cd.yaml` with the previous image).

## Branches and versions

`main` holds released code; `develop` publishes SNAPSHOTs of libraries; `release/*` and `hotfix/*`
are built like `main`; work branches only run the PR checks. Versions come from release-please
(`release-please-config.json`, `.release-please-manifest.json`, `version.txt`): the tag
`v<x.y.z>` is the version published (`-Drevision`), and the pom keeps the SNAPSHOT.

Renovate presets: `renovate/default.json` (action digests pinned to semver), `renovate/service.json`
(only the axiz parent moves; `axiz upgrade` applies its recipes) and `renovate/platform.json`.

## Organization settings

| Kind | Name | Used by |
|---|---|---|
| Secret | `NEXUS_URL`, `NEXUS_USERNAME`, `NEXUS_PASSWORD`, `NEXUS_DOCKER_REGISTRY` | builds, images |
| Secret (optional) | `SONAR_HOST_URL`, `SONAR_TOKEN` | SonarQube analysis and quality gate |
| Secret | `GITOPS_APP_ID`, `GITOPS_APP_PRIVATE_KEY` | GitHub App that may push to the GitOps repository only |
| Secret | `RELEASE_APP_ID`, `RELEASE_APP_PRIVATE_KEY` | GitHub App for release-please (its tags trigger the publication) |
| Secret (optional) | `ARGOCD_AUTH_TOKEN` | read-only Argo CD project token (`ci-read` role) |
| Variable | `GITOPS_REPOSITORY` (default `axiz-gitops`), `ARGOCD_SERVER` | deployments |
| Variable | `COSIGN_KMS_KEY` (`awskms:///alias/axiz-image-signing`), `COSIGN_ROLE_ARN`, `AWS_REGION` | image signing, SBOM and SLSA provenance attestations, signed provenance of library releases (OIDC). Mandatory: `service-ci` fails without them, and a release tag never publishes unsigned |
| Secret | `AXIZ_NAMING_GUARD` | names that must never appear in axiz repositories (one extended regex per line, kept out of the code); `pr.yaml` fails without it unless the variable `AXIZ_NAMING_GUARD_OPTIONAL=true` |
| Variable (sandbox only) | `AXIZ_ALLOW_UNSIGNED_IMAGES=true` | lets a sandbox repository publish unsigned images from `main` (never from a tag); clusters with the admission policy reject them |
| Variable per environment | `TERRAFORM_ROLE_ARN`, `AWS_REGION` | `infra.yaml` |

GitHub environments `dev`, `qa` and `prod`; `prod` requires reviewers. `qa` and `prod` only
deploy an image by digest (`image-digest`, output of `service-ci`); `service-cd` waits for Argo CD
through its REST API with `curl` and `jq` (no CLI is downloaded). Every tool downloaded by a
workflow has a pinned version and SHA-256, verified before it runs. Jobs that must reach Nexus
or the cluster through the private network take a `runner` input (an Actions Runner Controller
scale set in EKS).
