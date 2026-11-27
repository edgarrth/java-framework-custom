<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# resilience

Políticas de timeout, reintento con backoff, circuit breaker y bulkhead por nombre

```bash
axiz add resilience
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-resilience-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | core, gateway, ux |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.resilience.defaults.timeouts.total: 15s
```

## Uso

```java
axiz.resilience.instances.core.retry.max-attempts: 2
```

## Módulos

- [`axiz-resilience-spring-boot-autoconfigure`](../referencia/spring/axiz-resilience-spring-boot-autoconfigure.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-resilience`](../referencia/core/axiz-core-resilience.md)
