<!-- generated -->
# axiz-outbox-spring-boot-starter

Transactional outbox on Aurora PostgreSQL with background relay.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-outbox-spring-boot-starter</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Technical payload protection

`axiz.outbox.payload-protection=envelope` opts into authenticated payload encryption;
`none` is the default. Envelope mode requires the matching synchronous/native
`EnvelopeEncryptor` and fails startup when it is absent. Protected records cannot
be read in `none` mode; migration and retained read keys must be planned before
changing the mode. See [technical storage protection](../../../axiz-docs/docs/guias/proteccion-de-almacenes-tecnicos.md).
