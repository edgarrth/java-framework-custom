# axiz-core-context

Immutable request metadata and explicit context scopes.

This module owns the `pe.axiz.core.context` API. It has no application framework or provider dependency.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-context</artifactId>
</dependency>
```

## Types

Public types declared by this module:

- `RequestContext`
- `RequestMetadata`

## Boundaries

The core owns value types, policies or ports and performs no network or filesystem I/O.
Spring composition and concrete providers belong in separate modules. Test consumers against
these contracts without starting Spring.

## Verification

From `axiz-framework`:

```bash
./mvnw -pl core/axiz-core-context -am test
```

The full acceptance gate is `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Provider integration tests live in `quality/axiz-store-integration-tests` and require Docker.
