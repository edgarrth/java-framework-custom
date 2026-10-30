# axiz-workflows

Reusable GitHub Actions workflows, composite actions, Renovate presets and GitOps references of the
axiz platform. Callers pass an explicit map of the secrets declared by each reusable workflow.
Third-party actions use full commit SHAs. Internal references use a full SHA or an exact release
version (`@v1.0.0`) with a mandatory `axiz-workflows-trust` prerequisite. That job checks GitHub's
release API for the exact tag, `immutable: true` and `draft: false`, before the dependent jobs run.
Missing access, missing releases and mutable releases stop the pipeline. Custom job conditions
must also require the verification job to succeed. `check-workflow-security` tests these contracts
and scans both checked-in workflows and generator templates; exceptions to Semgrep are local to
the guarded references.

For a private `axiz-workflows` repository, supply `AXIZ_WORKFLOWS_READ_TOKEN` with **Contents: read**
on that repository only; otherwise the guard uses `github.token`. This credential does not grant
publication or deployment access. Enable [immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)
in GitHub and publish a new immutable release containing these checks before updating consumers.
An existing immutable release cannot be overwritten. The local changes do not configure GitHub
repository settings or prove that the currently referenced release is immutable.

Release-please updates internal action references and the guard's version together using
`# x-release-please-version`. Renovate proposes consumer updates; references and the verification
version must remain aligned. Prefer full SHAs once the published release can be resolved. The
verification uses the [release-by-tag API](https://docs.github.com/en/rest/releases/releases#get-a-release-by-tag-name).

After the Maven quality build, `check-encapsulation` rejects `EI_EXPOSE_REP`, `EI_EXPOSE_REP2`
and `CT_CONSTRUCTOR_THROW` at every reported priority. The shared SpotBugs filter permits only
reviewed exact class/member exceptions for these detectors; no global exception is allowed.

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
`v<x.y.z>` is the version published (`-Drevision`). Between releases the pom holds the SNAPSHOT
of the next version; the tagged commit (such as 1.0.0) holds the released version, and the first
change on `develop` after the tag moves the pom to the next SNAPSHOT (for example `1.1.0-SNAPSHOT`).

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
