<!-- generated -->
# axiz-saga-spring-boot-starter

Saga orchestrator with persistent state on PostgreSQL and recovery of stale sagas.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-saga-spring-boot-starter</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Technical payload protection

`axiz.saga.context-protection=envelope` opts into authenticated payload encryption;
`none` is the default. Envelope mode requires the matching synchronous/native
`EnvelopeEncryptor` and fails startup when it is absent. Protected records cannot
be read in `none` mode; migration and retained read keys must be planned before
changing the mode. See [technical storage protection](../../../axiz-docs/docs/guias/proteccion-de-almacenes-tecnicos.md).

JDBC is a separate integration: install `axiz-saga-jdbc-spring-boot-starter`.
The neutral base requires a `SagaStateStore`; it does not install a database provider.
