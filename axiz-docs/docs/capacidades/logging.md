<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# logging

Logs JSON estructurados con traza y contexto de la petición, log de acceso HTTP con enmascaramiento

```bash
axiz add logging
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-logging-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, ux, support, event-processor, batch |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.logging.http.enabled: true
```

## Uso

```java
log.atInfo().addKeyValue("orderId", id).log("order created");
```

## Módulos

- [`axiz-logging-spring-boot-autoconfigure`](../referencia/spring/axiz-logging-spring-boot-autoconfigure.md)
- [`axiz-core-api`](../referencia/core/axiz-core-api.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-logging`](../referencia/core/axiz-core-logging.md)
- [`axiz-core-sensitive`](../referencia/core/axiz-core-sensitive.md)
