# Security policy

This policy covers every component of this repository: the framework, the developer tools, the
documentation site, the payments demo and the shared CI/CD workflows.

## Release gate

Every detected **MEDIUM, HIGH or CRITICAL vulnerability blocks merging and publishing**,
including development dependencies, CI tools and every supported image platform. The same
gate applies when no upstream fix is available. Upgrade, replace or remove the affected
component and retain a complete new scan before releasing. An unknown severity, missing
report or failed scanner is not a clean result.

The response deadlines below govern incident handling; they do not authorize publishing an
affected artifact. Historical risk acceptances, mitigations and owner approval cannot waive
this gate. Preserve those records for audit without using them to suppress current findings.

## Reporting a vulnerability

Do not open a public issue, pull request or chat thread for a suspected vulnerability.

- Use **GitHub private vulnerability reporting** of this repository (Security tab, "Report a
  vulnerability"), or write to the security team mailbox `security@axiz.internal`.
- Include: affected module and version (or commit), how to reproduce it, impact as you understand
  it and whether you know of active exploitation.
- Never include real customer data, credentials or production secrets in the report. If a secret
  was exposed, say which one so it can be rotated first.

## What happens next

| Severity (CVSS 4.0 or equivalent) | Acknowledgement | Triage and owner | Fix released or mitigated |
|---|---|---|---|
| Critical | 24 hours | 2 business days | 7 calendar days |
| High | 2 business days | 5 business days | 30 calendar days |
| Medium | 5 business days | 10 business days | next minor release, at most 90 days |
| Low | 10 business days | best effort | next planned release |

1. The security team confirms the report, assigns a severity and an owner from `CODEOWNERS`.
2. The fix is prepared in a private fork or a GitHub security advisory and released as a patch of
   every supported version (see `SUPPORT.md`). Currently `@edgarrth` is the sole repository and
   security owner. Any other risk acceptance requires his explicit, traceable approval with
   exact scope, mitigation and expiry. This is owner acceptance, not independent review or
   separation of duties. A second reviewer becomes required when another authorized maintainer
   is added. Acceptance cannot waive the release gate above or mandatory external requirements.
3. Services that depend on an affected version are identified from the SBOMs and notified with
   the upgrade path. Renovate opens the upgrade pull requests.
4. The advisory is published to the internal organization after the fix is available
   (coordinated disclosure). Embargoed details are shared only with the people who need them.

## Scope and priorities

Areas reviewed with priority:

- `axiz-framework`: `core/**/*crypto*`, `core/**/*security*`, `spring/axiz-security-*`, `quality/`.
- `axiz-tools`: `axiz-initializr/` (internet facing) and `axiz-cli/`.
- `axiz-demo`: `services/*/src/main/java/**/security` and `deploy/`.
- `.github`: `workflows/`, `actions/` and `gitops/policies/`.

Dependency vulnerabilities are tracked automatically (Trivy on the SBOM, dependency review on pull
requests, Renovate security updates outside the weekly schedule); report them here only when the
framework itself makes them exploitable.
