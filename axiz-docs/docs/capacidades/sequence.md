<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# sequence

Secuencias de negocio (prefijo, ancho, reinicio diario o mensual) sobre JDBC, R2DBC nativo o DynamoDB

```bash
axiz add sequence
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-sequence-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.sequence.sequences.operation.prefix: OP
```

## Según la persistencia del servicio

### Aurora en servlet (JDBC/JPA)

- `pe.axiz:axiz-sequence-jdbc-spring-boot-starter`

### Aurora en reactivo (R2DBC)

- `pe.axiz:axiz-sequence-r2dbc-spring-boot-starter`

```yaml
axiz.sequence.store: r2dbc
```

### DynamoDB

- `pe.axiz:axiz-sequence-dynamodb-spring-boot-starter`

```yaml
axiz.sequence.store: dynamodb
axiz.sequence.table: ${axiz.dynamodb.table-prefix}axiz-sequence
```

## Uso

```java
sequences.next("operation"); // String con Sequences; Mono<String> con ReactiveSequences
```

## Módulos

- [`axiz-sequence-spring-boot-autoconfigure`](../referencia/spring/axiz-sequence-spring-boot-autoconfigure.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-sequence`](../referencia/core/axiz-core-sequence.md)
