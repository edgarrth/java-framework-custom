<!-- generated -->
# axiz-kit-jpa

JPA base entity with UUIDv7, auditing, versioning and encrypted attribute converters.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-kit-jpa</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AuditListener` | Fills the audit columns from the registered auditor provider and clock. |
| `AuditableEntity` | Entity with creation and modification audit columns (UTC instants and principal names). |
| `AuditingRegistry` | Holds the auditor provider and clock used by `AuditListener`. |
| `AuditorProvider` | Who is changing data: the authenticated principal name, or explicitly `unattributed` when no identity is available. |
| `BaseEntity` | Base JPA entity: application-assigned time-ordered UUIDv7 id (index friendly, known before insert) and optimistic locking. |
| `EncryptedConverter` | Encrypts a String attribute with AES-256-GCM: `@Convert(converter = EncryptedConverter.class)`. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl kits/axiz-kit-jpa verify`.

Background jobs should supply an explicit workload identity through their auditor provider;
missing or blank identity is marked `unattributed`, never silently attributed to a trusted system.
