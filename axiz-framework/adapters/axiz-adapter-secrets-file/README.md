# axiz-adapter-secrets-file

File and environment secret adapters with bounded refresh and owned lifecycle.

This module owns the `pe.axiz.adapter.secrets.file` API. It has no application framework or provider dependency.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-secrets-file</artifactId>
</dependency>
```

## Types

Public types declared by this module:

- `EnvironmentAsyncSecretResolver`
- `EnvironmentSecretResolver`
- `FileSecretResolver`
- `RefreshingFileSecretResolver`

## Verification

From `axiz-framework`:

```bash
./mvnw -pl adapters/axiz-adapter-secrets-file -am test
```

The full acceptance gate is `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Provider integration tests live in `quality/axiz-store-integration-tests` and require Docker.
