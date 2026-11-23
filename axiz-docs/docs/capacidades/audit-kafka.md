<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# audit-kafka

Eventos de auditoría publicados en MSK

```bash
axiz add audit-kafka
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-audit-kafka-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`audit`](audit.md), [`messaging-kafka`](messaging-kafka.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.audit.kafka.topic: ${AXIZ_AUDIT_TOPIC:audit}
```

## Llaves del ConfigMap por ambiente

- `axiz.audit.kafka.topic`

## Uso

```java
axiz.audit.kafka.topic: audit
```

## Módulos

- [`axiz-audit-kafka-spring-boot-autoconfigure`](../referencia/spring/axiz-audit-kafka-spring-boot-autoconfigure.md)
- [`axiz-core-audit`](../referencia/core/axiz-core-audit.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
