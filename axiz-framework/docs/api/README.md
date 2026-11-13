# Public API policy

2.0.0 is the first published framework version. No compatibility layer or migration from
unpublished Axiz drafts is supported. Imperative/native capabilities and external protocol
versions remain current functionality.

The 2.0.0 inventory includes the shared JDBC, Kafka and Redis transport guards and canonical
SQL resources. The empty EDA API artifact is removed. Intentional contract changes include
exact money construction, explicit rounding, the outbox transaction runner, audit queue metrics,
explicit rate-limit storage and removal of HTTP body logging. Closed implementations are final;
the supported extension contracts remain available. These signatures describe the first release,
without compatibility aliases for unpublished drafts. Updating this inventory prepares review;
it does not prove publication eligibility or replace the mandatory Nexus check.

`release-policy.json` explicitly selects one of two modes:

- `first-release`: only version 2.0.0 with no baseline. The gate queries Nexus's final
  `maven-releases` repository for the framework's coordinates. An existing published v2 release,
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
requires `NEXUS_URL`, with read credentials in `NEXUS_USERNAME`/`NEXUS_PASSWORD` when required. The principal must
be able to GET final-repository metadata, search and released artifacts; it needs no write permission:

```bash
python3 .github/scripts/check-api-compatibility.py
```

After the first immutable publication, change the policy to `published-baseline`, advance the
target version and set baseline to the actual published 2.0.0. Keep its published artifacts
immutable. Builds produce signed candidates; deploy independently promotes those exact bytes
without rebuilding. Signature checks do not change that separation or require remote rehearsals.
