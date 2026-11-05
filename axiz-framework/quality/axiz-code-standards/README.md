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
