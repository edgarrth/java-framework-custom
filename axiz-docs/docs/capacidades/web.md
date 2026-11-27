<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# web

Cabeceras de metadatos de la petición, correlación, paginación, versionado de API, CORS y convenciones JSON

```bash
axiz add web
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-web-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, ux, support |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.web.pagination.default-size: 20
axiz.web.pagination.max-size: 200
```

## Uso

```java
@GetMapping
PageResponse<Item> list(@Paging PageRequest page) { ... }
```

## Módulos

- [`axiz-web-spring-boot-autoconfigure`](../referencia/spring/axiz-web-spring-boot-autoconfigure.md)
- [`axiz-core-api`](../referencia/core/axiz-core-api.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-id`](../referencia/core/axiz-core-id.md)
