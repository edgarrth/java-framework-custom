# axiz-workflows

Reusable build and deploy workflows, composite actions, Renovate presets and GitOps references
for Axiz 2.0. The complete artifact, approval and promotion contract is documented in
[Pipelines 2.0](docs/pipelines-v2.md).

| Workflow | Purpose |
|---|---|
| `lib-build.yaml` | Verify the Maven reactor, quality, mutation, encapsulation, SBOM and scan; retain local staging and a signed manifest |
| `lib-deploy.yaml` | Independently verify a completed build and promote its exact Maven files, retain the original signed manifest and record deployment evidence |
| `service-build.yaml` | Verify a service, contracts and chart; publish build images and chart by digest, with SBOM, provenance and signed manifest |
| `service-deploy.yaml` | Independently verify an existing service build and promote or roll back through GitOps and Argo CD |
| `native.yaml` | Build, smoke and sign CLI components for Linux x64, macOS arm64 and Windows x64; `lib-build` verifies and incorporates them into one Maven product |
| `pr.yaml` | PR title, secrets, dependencies, static security, filesystem scan and workflow checks |
| `release.yaml` | Prepare release PRs, versions, changelogs and tags with release-please |
| `native-nightly.yaml` | Compile and test demo and generated services with GraalVM |

Framework, tools, demo and generated consumers use these workflows.

## Immutable workflow references

Third-party actions are pinned by full commit SHA. Internal references use an exact release
(`@axiz-workflows-v2.0.0`) with a direct `axiz-workflows-trust` dependency. The guard checks the release-by-tag
API for the expected tag, `immutable: true` and `draft: false`. Custom job conditions must also
require guard success. A private repository requires `AXIZ_WORKFLOWS_READ_TOKEN` with read
access only to `edgarrth/java-framework-custom`. Missing access or an unavailable release stops the job.

Enable [immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)
and publish the new release before updating remote consumers. These local changes do not
configure GitHub settings or constitute a remote release. Release-please updates internal
references and guard versions together through `# x-release-please-version`.

## Build and deploy

A build accepts an exact source SHA, version and public parameters. It has read-only dependency
credentials and, for services, write access to the build registry. It produces a signed
`build-manifest.json` and `build-bundle-<manifest-sha256>`. It has no release-publication or
GitOps credentials.

A separate deploy receives the completed producer run, attempt, externally approved manifest
hash, destination, environment and predecessor. Service deploy also requires a full values
commit. It verifies the entire producer run succeeded, signatures, hashes and evidence. It
never builds or repackages product bytes. Promotions and rollbacks select an existing verified
build; they do not take an arbitrary image tag.

Maven deployment currently supports final coordinates in `maven-releases`. This release contract publishes final coordinates only. Draft library releases create the tag through `force-tag-creation`; independent
promotion retains the manifest and native assets before publishing the draft. See the detailed
contract for protected environments, credentials and retry behavior.

## Actions and validation

- `build-inputs`: validates public parameters, source checkout and the optional framework candidate without OIDC.
  The isolated signer reconstructs identity from its own context, OIDC and Actions API evidence.
- `build-manifest`: inventories and validates build bytes, evidence, provenance and native components.
- `consume-build`: verifies a completed successful producer and its signed immutable bundle.
- `setup-maven`: configures JDK 25, Nexus and isolated verified framework candidates; credentials
  remain environment references. Candidate files never enter a shared Maven cache.
- `check-encapsulation`, `check-mutations`, `check-sbom`: enforce the corresponding quality gates.
- `check-workflow-security`: checks exact references and secret contracts; `check_pipeline_contracts.py`
  checks reusable workflows and actual consumer inputs, required secrets and phase boundaries.
- `test-report`: summarizes Surefire and Failsafe reports.

`self-check.yaml` runs syntax, security, contract and integrity regressions. The manifest tests
include native platform/signature mismatches, corrupted files, HTTP promotion retries, and
GitOps concurrency with a real local Git repository. Local tests do not prove a remote
GitHub/Nexus promotion or Kubernetes admission.

## GitOps references

`gitops/argocd/` pins the OCI chart digest and values source commit. The application reports the
expected image, both sources and requested deployment before success is recorded. Kyverno in
`gitops/policies/` verifies keyless service-build identity and provenance. Render the templates
from the same explicit `BUILD_DESTINATION` JSON supplied to the producer and isolated signer;
see [GitOps destination configuration](gitops/README.md). Unconfigured templates cannot be used
as deployment manifests.

Los servicios generados admiten además `workflow_run` para promover automáticamente a `dev` desde
un comprobante del productor firmado por el workflow inmutable. El resolver sólo lee Actions y
GitOps; el deploy independiente vuelve a verificar el bundle completo y su predecesor. Requiere
`GITOPS_READ_TOKEN` de solo lectura y `main` protegido; los contratos y la configuración están en
[Deploy automático independiente](docs/pipelines-v2.md).

## Monorepo source and versions

This directory is the `axiz-workflows` component of `edgarrth/java-framework-custom`.
Reusable workflows and executable product pipelines are in `.github/workflows` at the repository
root. Central Release Please configuration and state are also at the repository root; workflow
releases use independent tags `axiz-workflows-vX.Y.Z`. See [RELEASING.md](../RELEASING.md).

## Privileged job contracts

Workflows start with `permissions: {}`. Compilation workers have no OIDC rights. Only a fresh
`ubuntu-24.04` signer and protected promotion jobs receive `id-token: write`; reusable callers
delegate those rights to the reviewed workflow. SARIF writers have their own permission scope.

The signer checks out only the immutable workflow release. Producer files are bounded data:
Actions SHA/run/attempt, successful job steps, artifact timestamps and the server's archive digest
are checked before extraction. Uploaded scripts and executables are never executed. The signed
manifest includes `producer-authority`; unsigned worker reports cannot substitute for this gate.
Native smoke evidence retains the authoritative producer receipt without changing the component
format. Image, provenance, SBOM, manifest and automatic service proof signatures originate here.

`policies/privileged-jobs-v2.json` is the closed positive contract for signer and promotion jobs.
Changes to executable steps, actions, arguments, environment, runner or dependencies require an
explicit policy review; arbitrary commands, consumer checkouts and caches are rejected. Exact
workflow release pins are normalized only after checking their immutable-release guard. Consumers
use the policy from the immutable action release, not a policy uploaded by their build.
