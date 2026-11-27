<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# security

Resource server OAuth2 (emisores confiables, audiencias, claims), contexto del llamador y límite de tasa

```bash
axiz add security
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-security-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, ux, support, event-processor |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.security.issuers[0].issuer: ${AXIZ_OIDC_ISSUER}
axiz.security.issuers[0].audiences[0]: ${spring.application.name}
```

Perfil `local` (`application-local.yaml`):

```yaml
axiz.security.issuers[0].issuer: http://localhost:8180/realms/axiz
axiz.security.issuers[0].audiences[0]: local-services
axiz.security.public-api-docs: true
```

## Llaves del ConfigMap por ambiente

- `axiz.security.issuers`

## Uso

```java
@PreAuthorize("hasAuthority('SCOPE_orders.write')")
```

## Módulos

- [`axiz-security-spring-boot-autoconfigure`](../referencia/spring/axiz-security-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-json`](../referencia/core/axiz-core-json.md)
- [`axiz-core-security`](../referencia/core/axiz-core-security.md)
