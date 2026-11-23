<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# config

ConfigMaps montados como archivos (axiz-common, &lt;app&gt;, &lt;app&gt;-errors; sin acceso a la API de Kubernetes) y comprobación de llaves requeridas

```bash
axiz add config
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-config-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, ux, support, event-processor, batch |

Perfil `local` (`application-local.yaml`):

```yaml
axiz.config.environment: local
axiz.transport.allow-plain-http: true
axiz.transport.allow-insecure-data-stores: true
```

## Uso

```java
axiz.config.required: [axiz.http-client.clients.core.base-url]
```

## Módulos

- [`axiz-config-spring-boot-autoconfigure`](../referencia/spring/axiz-config-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
