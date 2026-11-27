<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# featureflags

Feature flags (OpenFeature) por porcentaje, canal, tenant y reglas, desde el ConfigMap

```bash
axiz add featureflags
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-featureflags-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.feature-flags.flags.example.enabled: false
```

## Llaves del ConfigMap por ambiente

- `axiz.feature-flags.flags`

## Uso

```java
if (featureFlags.isEnabled("new-pricing", context)) { ... }
```

## Módulos

- [`axiz-featureflags-spring-boot-autoconfigure`](../referencia/spring/axiz-featureflags-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-featureflags`](../referencia/core/axiz-core-featureflags.md)
