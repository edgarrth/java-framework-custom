<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-structure-hexagonal-api

Estilo hexagonal: casos de uso, puertos y adaptadores, transacción por caso de uso y eventos de dominio al confirmar (contratos, Java simple).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-hexagonal-api</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AggregateEvent` | Evento de dominio que nombra su tipo de agregado; se usa para enrutar y particionar. |
| `DomainEventSink` | Destino de los eventos de dominio cuando termina un caso de uso. |
| `DomainEvents` | Recoge los eventos de dominio generados durante el caso de uso actual. |
| `UseCase` | Caso de uso de aplicación (implementación de un puerto de entrada). |
| `UseCaseRunner` | Ejecuta un caso de uso: una transacción, con los eventos de dominio recogidos durante la ejecución y entregados al destino dentro de la transacción (outbox) o después del commit (publicación directa). |
| `UseCaseTransaction` | Ejecuta trabajo en una transacción (lo implementa el starter de Spring con una plantilla de transacción). |
| `Adapter` | Marca un adaptador: implementa un puerto de salida o invoca un puerto de entrada. |
| `InboundPort` | Marca una interfaz mediante la cual los adaptadores invocan la aplicación. |
| `OutboundPort` | Marca una interfaz que la aplicación necesita del exterior (repositorio, cliente, publicador). |
| `UseCase` | Marca la implementación de un caso de uso. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl structures/hexagonal/axiz-structure-hexagonal-api verify`.

## Fallo después del commit

Con un destino de eventos directo y no transaccional, la entrega puede fallar después de que la
transacción de negocio hizo commit. El runner emite un ERROR operativo que contiene solo la cantidad
de eventos y la clase del fallo, y luego lanza `PostCommitPublicationException`
(`CommittedTransactionOutcome`, código `AXZ-CORE-0410`). El sufijo numérico es un identificador del
catálogo, no HTTP 410. La operación de negocio nunca se repite para reparar este error. La entrega
directa no tiene garantía de durabilidad; se selecciona el destino del outbox transaccional cuando se
requieren entrega eventual y conciliación por parte del operador.
