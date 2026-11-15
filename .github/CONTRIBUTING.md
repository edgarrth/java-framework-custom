# Contributing

Thank you for improving the shared CI/CD workflows, composite actions, Renovate presets and GitOps references. These rules keep it safe for production banking services.

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
- Run locally before pushing: `actionlint` and the workflows-ci workflow.
- Warnings are fixed, not suppressed. A suppression needs a comment that explains why the code is
  correct (for example `@SuppressWarnings("EqualsGetClass")` with its reason).
- Update the module `README.md` and the documentation site when behavior or
  usage changes. Public APIs carry Javadoc.
- Reviews: at least one approval from a code owner; two approvals (one from the security team)
  for security, cryptography, authentication, payment flows and CI/CD changes.

## Compatibility

Public APIs and SPIs follow semantic versioning. The first release is 2.0.0. Later releases compare against the published baseline with the API compatibility gate. Document changes in the module README and upgrade guide, and provide an OpenRewrite recipe when the transformation can be automated.
