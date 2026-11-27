<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# errors

Problem details RFC 9457, catálogo de errores (ConfigMap &lt;app&gt;-errors) y mapeo de errores externos

```bash
axiz add errors
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-errors-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, ux, support, event-processor, batch |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.errors.type-base-uri: ${AXIZ_ERRORS_TYPE_BASE_URI:}
```

## Uso

```java
throw new BusinessException(MyErrors.LIMIT_EXCEEDED, "limit exceeded", Map.of("limit", limit));
```

## Módulos

- [`axiz-errors-spring-boot-autoconfigure`](../referencia/spring/axiz-errors-spring-boot-autoconfigure.md)
- [`axiz-core-api`](../referencia/core/axiz-core-api.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-id`](../referencia/core/axiz-core-id.md)
