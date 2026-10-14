# Estilos arquitectónicos

Un estilo (framework de la capa 3) define cómo se estructura y programa **todo** el servicio. Se
elige una vez al crearlo y se entrega como paquete completo: módulo `-api` en Java puro, starter de
Spring Boot, reglas ArchUnit que el servicio corre en `ArchitectureTest`, plantillas de código y
esta guía.

| Estilo | Cuándo usarlo | Qué define | Stack |
|---|---|---|---|
| [Layered](layered.md) | CRUD simple, poca lógica de dominio | `controller → service → repository`, `CrudService` | servlet |
| [Hexagonal](hexagonal.md) | dominio rico: reglas, límites, autorizaciones | `UseCase<I, O>`, puertos y adaptadores, transacción por caso de uso, eventos al confirmar | servlet |
| [CQRS](cqrs.md) | lecturas y escrituras con carga o modelo distintos | `Command`/`Query`, handlers, buses con pipeline, proyecciones | servlet, reactivo |
| [EDA](eda.md) | la entrada son eventos | handlers tipados, inbox, outbox, reintentos, DLQ, coreografía | servlet, reactivo |
| [BFF](bff.md) | canales que agregan otros servicios | composición en paralelo, partes opcionales, adaptación por canal, caché | servlet, reactivo |
| [Batch](batch.md) | archivos, conciliaciones, cargas | Spring Batch, lectores reiniciables sobre S3, CronJob, códigos de salida | servlet |

Decisiones comunes:

- **Hexagonal en lugar de clean**: son el mismo principio (dominio al centro, dependencias hacia
  adentro); mantener ambos duplicaría plantillas y reglas.
- **Event sourcing** queda fuera de esta versión; se agrega solo si un dominio lo exige.
- Los handlers se registran por el tipo genérico de la clase destino (funciona con proxies
  transaccionales). Un handler que falta es un **error de configuración al arrancar**, no un 404 en
  tiempo de ejecución.

Todas las reglas generales aplican a cualquier estilo: inyección solo por constructor, sin estado
mutable en singletons, sin `System.out`, sin hooks globales de Reactor, sin `block()` en código
reactivo, sin clientes HTTP paralelos, sin AWS SDK v1 (ver
[Reglas de arquitectura](../calidad/arquitectura.md)).
