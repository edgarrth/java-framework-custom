# axiz-kit-sql

SQL identifier validation independent of database drivers.

This module owns the `pe.axiz.kit.sql` API. It has no application framework or provider dependency.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-kit-sql</artifactId>
</dependency>
```

## Types

Public types declared by this module:

- `SqlIdentifiers`

## Verification

From `axiz-framework`:

```bash
./mvnw -pl kits/axiz-kit-sql -am test
```

The full acceptance gate is `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Provider integration tests live in `quality/axiz-store-integration-tests` and require Docker.
