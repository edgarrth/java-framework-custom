<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# inbox

Deduplicación del consumidor (inbox) con leases y retención, sobre JDBC, R2DBC o DynamoDB

```bash
axiz add inbox
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-inbox-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | event-processor |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.inbox.retention: 14d
```

## Según la persistencia del servicio

### Aurora en servlet (JDBC/JPA)

- `pe.axiz:axiz-inbox-jdbc-spring-boot-starter`

### Aurora en reactivo (R2DBC)

- `pe.axiz:axiz-inbox-r2dbc-spring-boot-starter`

```yaml
axiz.inbox.store: r2dbc
```

### DynamoDB

- `pe.axiz:axiz-inbox-dynamodb-spring-boot-starter`

```yaml
axiz.inbox.store: dynamodb
axiz.inbox.table: ${axiz.dynamodb.table-prefix}axiz-inbox
```

## Uso

```java
// los consumidores se deduplican de forma automática por el id del evento
```

## Módulos

- [`axiz-inbox-spring-boot-autoconfigure`](../referencia/spring/axiz-inbox-spring-boot-autoconfigure.md)
- [`axiz-core-inbox`](../referencia/core/axiz-core-inbox.md)
- [`axiz-core-messaging`](../referencia/core/axiz-core-messaging.md)
