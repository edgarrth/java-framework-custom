# Security policy

This policy covers the axiz documentation site, architecture decision records and dashboards.

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
2. The fix is prepared in a private fork or a GitHub security advisory, reviewed by two people
   (one of them from the security team) and released as a patch of every supported version
   (see `SUPPORT.md`).
3. Services that depend on an affected version are identified from the SBOMs and notified with
   the upgrade path. Renovate opens the upgrade pull requests.
4. The advisory is published to the internal organization after the fix is available
   (coordinated disclosure). Embargoed details are shared only with the people who need them.

## Scope and priorities

Areas reviewed with priority in this repository: docs/arquitectura/, docs/guias/seguridad.md.

Dependency vulnerabilities are tracked automatically (Trivy on the SBOM, dependency review on pull
requests, Renovate security updates outside the weekly schedule); report them here only when the
framework itself makes them exploitable.
