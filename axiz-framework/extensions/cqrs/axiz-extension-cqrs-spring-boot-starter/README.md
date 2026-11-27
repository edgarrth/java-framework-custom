<!-- generated -->
# axiz-extension-cqrs-spring-boot-starter

CQRS sobre hexagonal: comandos, consultas, buses con un pipeline ordenado y proyecciones.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-extension-cqrs-spring-boot-starter</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AuditBehavior` | Registra las ejecuciones de `AuditedCommand` con su resultado (los detalles se sanean). |
| `AuthorizationBehavior` | Etapa de autorización del pipeline: `RequiresAuthority` sobre el tipo de petición (403 cuando el llamador no la tiene) y luego cada `AccessPolicy` correspondiente (comprobaciones a nivel de objeto). |
| `AxizCqrsAutoConfiguration` | Buses CQRS sobre todos los beans `CommandHandler` y `QueryHandler` con el pipeline ordenado: observación, validación, autorización, idempotencia, transacción, auditoría y métricas. |
| `AxizReactiveCqrsAutoConfiguration` | CQRS reactivo: `ReactiveCommandBus` y `ReactiveQueryBus` sobre todos los beans `ReactiveCommandHandler` y `ReactiveQueryHandler`, con el mismo pipeline ordenado que los buses bloqueantes, construido con comportamientos no bloqueantes (la transacción es reactiva, sobre el gestor de transacciones R2DBC). |
| `IdempotencyBehavior` | Comandos idempotentes: el primer resultado se guarda (JSON) y se devuelve de nuevo para la misma clave, el mismo comando y el mismo llamador; un comando distinto con la misma clave produce un problema 422. |
| `MetricsBehavior` | Cuenta y mide el tiempo de cada comando y consulta como un evento genérico con el nombre de la petición (`create-card`); las peticiones y los resultados que implementan `MetricSource` añaden importes y etiquetas. |
| `ObservationBehavior` | Paso más externo: un span `axiz.command` o `axiz.query` alrededor de todo el pipeline. |
| `ReactiveBehaviors` | Comportamientos estándar del pipeline reactivo, uno por cada aspecto transversal. |
| `ReactiveBuses` | Buses reactivos de comandos y consultas: el mismo pipeline que los buses bloqueantes (observación, validación, autorización, idempotencia, transacción, auditoría, métricas) compuesto como operadores sobre el `Mono` del handler, de modo que ningún paso bloquea un event loop. |
| `ReactiveCommandBus` | Envía los comandos a su `ReactiveCommandHandler` a través del pipeline reactivo. |
| `ReactivePipelineBehavior` | Paso transversal alrededor de los handlers reactivos, con las mismas constantes de orden que `PipelineBehavior`. |
| `ReactiveQueryBus` | Envía las consultas a su `ReactiveQueryHandler` a través del pipeline reactivo. |
| `TransactionBehavior` | Los comandos se ejecutan en una transacción de lectura y escritura; las consultas, en una de solo lectura. |
| `ValidationBehavior` | Bean Validation de comandos y consultas antes de ejecutar cualquier otra cosa (400 ante violaciones). |

## Pipeline nativo

La autorización reactiva acepta solo implementaciones de `ReactiveAccessPolicy` de la API. Las
políticas de acceso imperativas se rechazan cuando hay handlers reactivos registrados; siguen siendo
válidas para los buses imperativos. La idempotencia requiere `AsyncIdempotencyService`; los comandos
auditados requieren `ReactiveAuditTrail`. Si faltan los puertos nativos, el arranque falla en lugar
de omitir un comportamiento en silencio.

El pipeline no delega trabajo en el scheduler elástico. La cancelación de la finalización nativa
llega al transporte activo, y las ejecuciones de idempotencia canceladas liberan su lease protegido
por propietario sin eliminar los registros de repetición completados. La auditoría se ejecuta dentro
de la transacción reactiva y espera la entrega.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl extensions/cqrs/axiz-extension-cqrs-spring-boot-starter verify`.
