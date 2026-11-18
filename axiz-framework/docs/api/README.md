# Public API policy

The public API inventory records the public and protected signatures and constants of every published JAR. CI compares each build with this inventory and applies the publication mode selected in `release-policy.json`. The initial framework release is 2.0.0.

The [initial API review](review-2.0.0.md) explains the accepted module moves, breaking changes
from the unpublished design snapshot, and the limits of this local review.

`release-policy.json` explicitly selects one of two modes:

- `first-release`: only version 2.0.0 with no baseline. The gate authenticates to the GitHub API and enumerates Maven packages and their versions,
  filtering the exact source repository and framework coordinates. An existing published v2 release,
  failed query or ambiguous response rejects this mode. Candidate repositories, local Maven
  caches and unrelated products do not count as published framework versions.
- `published-baseline`: requires a genuinely earlier v2 version in that final repository.
  The gate first downloads that baseline from the final repository into a separate directory,
  recording hashes. It does not trust candidate/local Maven caches. Maven's strict `api-compat`
  profile must produce fresh Japicmp reports for every published
  JAR. Missing baseline resolution fails; it never falls back to first-release mode.

For both modes, class-file signatures are extracted with `javap -protected -constants` and
compared with the approved `public-api.json` inventory. Adding/removing modules or changing
public/protected signatures requires review. Evidence stores the observed signatures, their
SHA-256 and each actual JAR's SHA-256, plus source/run/attempt and the gate result. CI retains
this evidence for 90 days and it remains a mandatory gate before promotion.

To prepare local review evidence after a build:

```bash
python3 .github/scripts/check-api-compatibility.py --inventory-only
```

This command explicitly does **not** pass the release gate. Review the exported signatures
before updating `public-api.json`; never approve changes just to silence CI. The normal gate
requires `PACKAGES_URL`, with read credentials in `PACKAGES_USERNAME`/`PACKAGES_TOKEN`. The token must have `read:packages` and the principal must
be able to enumerate GitHub Maven packages and versions and GET released artifacts; it needs no write permission:

```bash
python3 .github/scripts/check-api-compatibility.py
```

After the first immutable publication, change the policy to `published-baseline`, advance the
target version and set baseline to the actual published 2.0.0. Keep its published artifacts
immutable. Builds produce signed candidates; deploy independently promotes those exact bytes
without rebuilding. Signature checks preserve the separation between building and promoting artifacts.
