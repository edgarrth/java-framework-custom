<!-- generated -->
# axiz-structure-eda-spring-boot-starter

Estilo orientado a eventos: handlers tipados, consumo idempotente, publicación por outbox, reintentos y DLQ.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-eda-spring-boot-starter</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizEdaAutoConfiguration` | Estilo orientado a eventos: handlers transaccionales (las escrituras de negocio, el inbox y el outbox hacen commit juntos) y coreografía mediante `Reactions`, o bien, en servicios reactivos, handlers no bloqueantes en una transacción reactiva con `ReactiveReactions`. |
| `Reactions` | Emite eventos en reacción a uno consumido (coreografía) mediante el outbox, dentro de la transacción del handler. |
| `ReactiveReactions` | `Reactions` reactivo: emite eventos en reacción a uno consumido mediante el outbox R2DBC, dentro de la transacción reactiva del handler. |

Tipos relacionados: `ReactiveEventHandlerPostProcessor`, `ReactiveTransactionalEventHandler`, `TransactionalEventHandler`, `TransactionalEventHandlerPostProcessor` están en `axiz-messaging-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl structures/eda/axiz-structure-eda-spring-boot-starter verify`.

El starter reutiliza la configuración `axiz.messaging.*` y los contratos de mensajería del núcleo. EDA no tiene un artefacto de API separado.
