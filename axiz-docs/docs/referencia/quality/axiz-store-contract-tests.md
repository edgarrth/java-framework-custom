<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-store-contract-tests

Comprobaciones de contrato que todo almacén de idempotencia y de inbox debe superar (propiedad, expiración, toma de control); se ejecutan contra fakes en las pruebas unitarias y contra backends reales en las de integración.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-store-contract-tests</artifactId>
  <scope>test</scope>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AsyncInboxStoreContract` | Reutiliza el contrato de propietario, lease y retención, y envía la I/O nativa a un ejecutor no bloqueante entregado, con un driver JUnit acotado. |
| `ContractCheck` | Una propiedad con nombre que un almacén debe cumplir. |
| `IdempotencyStoreContract` | Propiedades de todo `IdempotencyStore`: inicio exclusivo mientras el lock está vigente, finalización y liberación protegidas por el token del propietario, toma de control tras la expiración sin interferencia del propietario anterior, y retención. |
| `InboxStoreContract` | Propiedades de todo `InboxStore`: una sola entrega procesa un mensaje a la vez, la finalización y la liberación están protegidas por el token del propietario, un lease expirado se toma sin interferencia del propietario anterior y los mensajes procesados se reconocen. |

## Uso

Un almacén propio supera las mismas comprobaciones que los almacenes del framework:

```java
@TestFactory
Stream<DynamicTest> contract() {
  return IdempotencyStoreContract.checks(clock -> new MyIdempotencyStore(..., clock)).stream()
      .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
}
```

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl quality/axiz-store-contract-tests verify`.
