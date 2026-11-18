# axiz-outbox-jdbc-spring-boot-starter

JDBC outbox integration with the shared relay.

This module owns the `pe.axiz.spring.outbox.jdbc.starter` API.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-outbox-jdbc-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-outbox-jdbc-spring-boot-autoconfigure`](../axiz-outbox-jdbc-spring-boot-autoconfigure/README.md).

## Operations

Provision the capability schema through migrations and configure a bounded connection pool
with verified TLS. Transactional composition requires the same effective resource identity,
not merely two clients pointing to the same database. Select the native R2DBC provider for
reactive flows; JDBC executes on the imperative path.
