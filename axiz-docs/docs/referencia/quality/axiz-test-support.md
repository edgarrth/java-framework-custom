<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-test-support

Fixtures de prueba para la capa 1: reloj mutable, publicador que registra eventos, aserciones de métricas y un fake de R2DBC en memoria.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-test-support</artifactId>
  <scope>test</scope>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `MetricAssertions` | Lee métricas genéricas de eventos en las pruebas sin depender de un framework de pruebas. |
| `InMemoryCrudService` | Fixture CRUD respaldado por un mapa, con comprobación atómica de versión al actualizar y borrar. |
| `MutableClock` | Reloj que las pruebas pueden adelantar o fijar. |
| `RecordingEventPublisher` | Publicador que registra los eventos; puede configurarse para fallar y así probar reintentos. |
| `FakeR2dbc` | Driver R2DBC en memoria para pruebas unitarias que se presenta como PostgreSQL (marcadores `$n`): las sentencias se reconocen con una expresión regular sobre su SQL y se responden con funciones programadas sobre los valores ligados; cada ejecución, binding y evento de transacción se registra para las aserciones. |

## Fixture de identidad de carga de trabajo

`AwsIdentityFixture` proporciona STS y SSM en loopback y un centinela IMDS con credenciales temporales
ficticias. El entorno de su proceso hijo elimina la identidad de AWS heredada y las opciones de
credenciales de Java. Se comprueban los contadores de peticiones y `invalidRequests()` además del
resultado del cliente. Este fixture verifica la selección local de credenciales y la firma; no se
autentica contra AWS.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl quality/axiz-test-support verify`.

`InMemoryCrudService` requiere un extractor de versión además de sus funciones de ID y de
actualización. La actualización y el borrado comprueban de forma atómica la versión esperada
entregada: las versiones ausentes son inválidas, las actualizaciones desfasadas generan conflicto y los
borrados desfasados no cumplen su precondición. La función de actualización debe avanzar la versión
del fixture. Este doble de prueba no proporciona autorización de producción.
