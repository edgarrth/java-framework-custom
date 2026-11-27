<!-- generated -->
# axiz-extension-cqrs-api

CQRS sobre hexagonal: comandos, consultas, buses con un pipeline ordenado y proyecciones (contratos, Java simple).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-extension-cqrs-api</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AccessPolicies` | Políticas de acceso de una aplicación: ejecuta las que corresponden a una petición e informa los tipos de petición que nada protege. |
| `AccessPolicy` | Autorización a nivel de objeto de un tipo de comando o consulta: decide si el llamador puede actuar sobre los recursos que nombra la petición (cliente, cuenta, orden), además de las autoridades de `RequiresAuthority`. |
| `ReactiveAccessPolicy` | Autorización no bloqueante mediante `Publisher<Void>`, independiente de Spring y de Reactor. |
| `AuditedCommand` | Comando registrado en la pista de auditoría con su resultado. |
| `Buses` | Buses de comandos y consultas en Java simple. |
| `Caller` | Quién envía un comando o una consulta, tal como lo ve una `AccessPolicy`. |
| `CommandBus` | Envía los comandos a su handler a través del pipeline. |
| `CommandHandler` | Atiende un tipo de comando. |
| `HandlerNotFoundException` | No hay handler registrado para un tipo de petición: es un error de configuración, no del cliente. |
| `IdempotentCommand` | Comando protegido por una clave de idempotencia: una clave repetida devuelve el primer resultado. |
| `Invocation` | Una petición que recorre el pipeline. |
| `OpenAccess` | Decisión explícita y revisada de que esta petición no necesita autoridad ni `AccessPolicy`: por ejemplo, un comando que solo envía un consumidor de eventos y nunca se expone por HTTP, o una consulta de catálogo público. |
| `PipelineBehavior` | Paso transversal alrededor de los handlers. |
| `Projection` | Mantiene actualizado un modelo de lectura a partir de eventos (eventos de dominio en proceso, o eventos de integración mediante el estilo EDA). |
| `QueryBus` | Envía las consultas a su handler a través del pipeline. |
| `QueryHandler` | Atiende un tipo de consulta; nunca cambia el estado. |
| `ReactiveCommandHandler` | Atiende un tipo de comando sin bloquear (servicios reactivos sobre WebFlux y R2DBC). |
| `ReactiveQueryHandler` | Atiende un tipo de consulta sin bloquear; nunca cambia el estado. |
| `Request` | Mensaje que atiende exactamente un handler. |
| `RequiresAuthority` | Autoridades (basta una de ellas) que el llamador necesita para enviar un comando o una consulta. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl extensions/cqrs/axiz-extension-cqrs-api verify`.
