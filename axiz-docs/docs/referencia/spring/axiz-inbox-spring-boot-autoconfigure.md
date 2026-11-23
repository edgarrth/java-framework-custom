<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-inbox-spring-boot-autoconfigure

Consumidores idempotentes: inbox sobre Aurora PostgreSQL o DynamoDB (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-inbox-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ReactiveInbox` | Fachada de aplicación diferida sobre DynamoDB o R2DBC nativos; conserva la cancelación y el contexto transaccional de Reactor. |
| `AxizInboxAutoConfiguration` | Inbox: el despachador de mensajería omite los ids de evento que un consumidor ya procesó. |
| `AxizInboxProperties` | Consumo idempotente (`axiz.inbox.*`): ids de mensajes procesados por consumidor. |
| `InboxPurger` | Elimina periódicamente, en un hilo virtual, las filas completadas y vencidas del inbox (almacenes JDBC y R2DBC). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-inbox-spring-boot-autoconfigure verify`.

El cableado de cada proveedor está en su propio módulo de autoconfiguración JDBC, R2DBC o DynamoDB. Este
módulo base exige una ventana coherente de lease y retención (`retention > lease > 0`) y un intervalo de
purga positivo. El [contrato del inbox](../core/axiz-core-inbox.md) describe la toma de control
y los cambios de TTL.
