<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# sensitive

Enmascaramiento de campos sensibles en respuestas, logs y auditoría según el nivel de sensibilidad

```bash
axiz add sensitive
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-sensitive-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, ux, support, event-processor, batch |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.sensitive.response: MEDIUM
```

## Uso

```java
record Customer(@Sensitive(level = HIGH, mask = PARTIAL) String documentNumber) {}
```

## Módulos

- [`axiz-sensitive-spring-boot-autoconfigure`](../referencia/spring/axiz-sensitive-spring-boot-autoconfigure.md)
- [`axiz-core-carddata`](../referencia/core/axiz-core-carddata.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-secrets`](../referencia/core/axiz-core-secrets.md)
- [`axiz-core-sensitive`](../referencia/core/axiz-core-sensitive.md)
