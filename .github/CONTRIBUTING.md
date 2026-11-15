# Contributing

These rules apply to every component of the repository: framework, tools, documentation, demo
and shared workflows. They keep it safe for production banking services.

## Before you start

- Discuss new capabilities, breaking changes and new third-party dependencies in an issue first.
  Public API changes and new dependencies require code-owner review, updated usage documentation
  and the corresponding compatibility and integration checks.
- Never commit secrets, real customer data or production endpoints. Local examples use the
  placeholders of the local environment only. gitleaks runs on every pull request.
- Do not add deprecated libraries, or APIs and versions that are not yet released.

## Branches and commits

- `main` holds released code. Work happens in short-lived branches; `develop`, `release/*` and
  `hotfix/*` run the same CI as `main`.
- Commit messages and pull request titles follow Conventional Commits (`feat:`, `fix:`,
  `refactor:`, `docs:`, ...). release-please derives versions and changelogs from them; mark a
  breaking change with `!` and a `BREAKING CHANGE:` footer. See [RELEASING.md](../RELEASING.md).
- Commits are signed (GPG, SSH or S/MIME) and branches are protected: no direct pushes to `main`
  or `develop`, linear history, required checks green.
- On WSL with `core.filemode=false`, add new or renamed wrappers and shell entry points with
  `git -c core.filemode=true add --all` so their executable bit reaches the commit. The
  build-input check verifies the index.

## Pull requests

- Every change comes with tests that would have failed without it. Quality gates enforce coverage
  (lines and branches), mutation testing on the core, architecture rules and static analysis.
- Warnings are fixed, not suppressed. A suppression needs a comment that explains why the code is
  correct (for example `@SuppressWarnings("EqualsGetClass")` with its reason).
- Import unambiguous types instead of qualifying them inline. Error Prone's
  `UnnecessarilyFullyQualified` check defaults to `WARN`; cleaned modules set
  `axiz.errorprone.fqn.severity` to `ERROR`. Keep qualification when names collide and leave
  generated sources to their generator.
- Update the module `README.md` and the documentation site when behavior or usage changes.
  Public APIs carry Javadoc.
- Reviews: at least one approval from a code owner; two approvals (one from the security team)
  for security, cryptography, authentication, payment flows and CI/CD changes.

## Local checks

| Component | Command |
|---|---|
| `axiz-framework` | `./mvnw -B -ntp clean verify -Pquality` |
| `axiz-tools` | `./mvnw -B -ntp clean verify -Pquality` |
| `axiz-demo` | `./mvnw -B -ntp clean verify` and `scripts/smoke.sh` |
| `axiz-docs` | `mkdocs build --strict` |
| `.github` | `actionlint` and `python .github/scripts/workflows_policy_checks.py --output <directory>` |

The framework policy scripts use the dependencies pinned in
`axiz-framework/.github/scripts/requirements.txt`. From `axiz-framework`:

```bash
python3 -m venv /tmp/axiz-framework-policy
/tmp/axiz-framework-policy/bin/python -m pip install -r .github/scripts/requirements.txt
/tmp/axiz-framework-policy/bin/python -m unittest discover -s .github/scripts -p 'test_*.py'
```

`axiz-framework/.github/scripts/samples.sh` generates the catalog defaults and checks that
`native-nightly.yaml` selects exactly the generated service projects. `MODE=all` generates the
full structure, extension, stack and persistence matrix. `reactive-native-sample.sh` requires
GraalVM JDK 25 and Docker; it generates a reactive service, runs its JVM tests and boots the
native executable against disposable dependencies.

## Compatibility

Public APIs and SPIs follow semantic versioning. The API gate compares class-file signatures with
the approved inventory and, after the first publication, with the published baseline. See the
[API policy](../axiz-framework/docs/api/README.md). Document changes in the module README and
provide an OpenRewrite recipe when the transformation can be automated.
