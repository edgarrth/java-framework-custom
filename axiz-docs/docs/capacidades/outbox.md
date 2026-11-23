<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# outbox

Outbox transaccional en Aurora (JDBC o R2DBC) con relay hacia MSK, SNS o SQS (al menos una vez)

```bash
axiz add outbox
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-outbox-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.outbox.relay.enabled: true
```

## Según la persistencia del servicio

### Aurora en servlet (JDBC/JPA)

Agrega también: [`jpa`](jpa.md)

- `pe.axiz:axiz-outbox-jdbc-spring-boot-starter`

### Aurora en reactivo (R2DBC)

Agrega también: [`r2dbc`](r2dbc.md)

- `pe.axiz:axiz-outbox-r2dbc-spring-boot-starter`

```yaml
axiz.outbox.store: r2dbc
```

## Uso

```java
// dentro de la transacción del cambio de negocio: se publica si y solo si se confirma
transactionalEvents.enqueue("orders", event.id(), "order-created.v1", "order", order.id(), event);
// servicios reactivos: reactiveTransactionalEvents.enqueue(...) devuelve un Mono en la misma transacción
```

## Módulos

- [`axiz-outbox-spring-boot-autoconfigure`](../referencia/spring/axiz-outbox-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-crypto`](../referencia/core/axiz-core-crypto.md)
- [`axiz-core-messaging`](../referencia/core/axiz-core-messaging.md)
- [`axiz-core-observability`](../referencia/core/axiz-core-observability.md)
- [`axiz-core-outbox`](../referencia/core/axiz-core-outbox.md)
- [`axiz-core-resilience`](../referencia/core/axiz-core-resilience.md)
