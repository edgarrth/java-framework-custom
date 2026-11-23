<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# tenancy

Multi-tenancy desde el token o una cabecera confiable, propagado a logs, métricas y datos

```bash
axiz add tenancy
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-tenancy-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.tenancy.enabled: true
```

## Uso

```java
String tenant = TenantContext.current().orElseThrow();
```

## Módulos

- [`axiz-tenancy-spring-boot-autoconfigure`](../referencia/spring/axiz-tenancy-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-tenancy`](../referencia/core/axiz-core-tenancy.md)
