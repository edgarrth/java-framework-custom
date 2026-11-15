# Contributing

Thank you for improving the axiz framework (core libraries, Spring Boot starters, architectural styles, code generation, BOM and parents). These rules keep it safe for production banking services.

## Before you start

- Discuss new capabilities, breaking changes and new third-party dependencies in an issue first.
  Public API changes and new dependencies require code-owner review, updated usage documentation
  and the corresponding compatibility and integration checks.
- Never commit secrets, real customer data or production endpoints. Local examples use the
  placeholders of the local environment only. gitleaks runs on every pull request.
- Do not add deprecated libraries, or APIs and versions that are not yet released.

## Branches and commits

- `main` holds released code, `develop` publishes snapshots, `release/*` and `hotfix/*` follow
  the release process. Work happens in short-lived branches from `develop` (or from `main` for a
  hotfix).
- Commit messages and pull request titles follow Conventional Commits (`feat:`, `fix:`,
  `refactor:`, `docs:`, ...). release-please derives versions and changelogs from them; mark a
  breaking change with `!` and a `BREAKING CHANGE:` footer.
- Commits are signed (GPG, SSH or S/MIME) and branches are protected: no direct pushes to `main`
  or `develop`, linear history, required checks green.

## Pull requests

- Every change comes with tests that would have failed without it. Quality gates enforce coverage
  (lines and branches), mutation testing on the core, architecture rules and static analysis.
- Run locally before pushing: `./mvnw -B -ntp clean verify -Pquality`.
- Import unambiguous types instead of qualifying them inline. Error Prone's
  `UnnecessarilyFullyQualified` check defaults to `WARN`; cleaned modules set
  `axiz.errorprone.fqn.severity` to `ERROR` to prevent regressions. Keep qualification when
  names collide, and leave generated sources to their generator. Do not disable the check to
  accommodate avoidable qualifiers.
- Warnings are fixed, not suppressed. A suppression needs a comment that explains why the code is
  correct (for example `@SuppressWarnings("EqualsGetClass")` with its reason).
- Update the module `README.md` and the documentation site when behavior or
  usage changes. Public APIs carry Javadoc.
- Reviews: at least one approval from a code owner; two approvals (one from the security team)
  for security, cryptography, authentication, payment flows and CI/CD changes.

## Compatibility

2.0.0 is the first release. No compatibility with unpublished drafts is retained.
The mandatory API gate uses `docs/api/release-policy.json`, reviewed class-file signatures and
a fail-closed query of the final publication repository. Later releases run `api-compat` against
a genuinely earlier published v2 baseline. See [API policy](docs/api/README.md).

## Pipeline and generated sample checks

Framework Python policy dependencies are pinned in `.github/scripts/requirements.txt`. CI installs
them in an isolated environment. To run the same policies locally:

```bash
python3 -m venv /tmp/axiz-framework-policy
/tmp/axiz-framework-policy/bin/python -m pip install -r .github/scripts/requirements.txt
/tmp/axiz-framework-policy/bin/python -m unittest discover -s .github/scripts -p 'test_*.py'
```

`.github/scripts/samples.sh` generates the catalog defaults and checks that `native-nightly.yaml`
selects exactly the generated service projects. Libraries and starters remain part of JVM sample
validation. Update the native selection when changing sample names or service roles. `MODE=all`
generates the full structure, extension, stack and persistence matrix for the separate nightly job.

The mandatory `native-reactive` gate and nightly also run
`bash .github/scripts/reactive-native-sample.sh`. It requires GraalVM JDK 25 and Docker, installs
the framework from source in the local Maven repository, and generates a fresh fixture under
`target/reactive-native-acceptance`. JVM tests exercise BlockHound, event contracts and decimal
precision; the compiled ELF must then boot with disposable dependencies and pass its health,
liveness and readiness probes. It uses the PRNG default compiled by the service parent.
