<!-- generated -->
# axiz-code-standards

Checkstyle, SpotBugs, Semgrep, Spectral and gitleaks configuration.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-code-standards</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Error Prone

The parent quality profile enables `UnnecessarilyFullyQualified` as a warning alongside
Error Prone and NullAway. Use imports when there is no type-name collision. Qualified names
remain appropriate when both JDBC and R2DBC expose types with the same simple name.

## SpotBugs

The general `exclude.xml` is empty. Handwritten framework false positives use a justified
`@SuppressFBWarnings` on the smallest applicable member. Medium and high findings block quality.

The service parent alone uses `generated-dtos.xml`: it permits the mutable collection API of
OpenAPI wire models carrying the exact `pe.axiz.core.commons.Generated` marker. Only
`EI_EXPOSE_REP` and `EI_EXPOSE_REP2` are exempt; security and constructor findings remain active.
The encapsulation checker rejects widening this exception to other detectors or annotations.
