# axiz-schema

Canonical SQL resources for Axiz tables, under `axiz/sql/`. This JAR contains resources only: no drivers, runtime or startup migration runner. JDBC adapters and project generation consume the same bytes.

The application owns execution of its initial Flyway migrations. Select the fragments for enabled capabilities, review them and execute using an account with DDL privileges. Runtime accounts need only their operation privileges. Resources are outside Flyway's default `db/migration` path and never run automatically.

Keep the selected SQL fragments in the application's versioned migration history. Flyway manages migration ordering and checksums; use a separate deployment identity for DDL and keep runtime identities limited to their data operations. Review locks, concurrency and data conversion requirements before applying a schema change.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-schema</artifactId>
</dependency>
```
