<!-- generated -->
# axiz-core-tenancy

Tenant context, resolution and propagation.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-tenancy</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `TenantContext` | Tenant of the current unit of work, bound for the duration of a call. |
| `TenantRequiredException` | The operation needs a tenant but none is bound. |
| `TenantResolver` | Resolves the tenant of an inbound request: first from the validated token claim, then (only when explicitly trusted, e.g. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-tenancy verify`.
