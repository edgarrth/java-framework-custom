<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# metrics

Métricas genéricas de eventos (conteo, duración, importes por moneda, valores) con tags acotados

```bash
axiz add metrics
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-metrics-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, ux, support, event-processor, batch |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
management.endpoints.web.exposure.include: health,info,prometheus
management.endpoint.health.probes.enabled: true
```

## Uso

```java
metrics.amount("order.created", order.total(), e -> e.channel(channel).outcome(Outcome.SUCCESS));
```

## Módulos

- [`axiz-metrics-spring-boot-autoconfigure`](../referencia/spring/axiz-metrics-spring-boot-autoconfigure.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-metrics`](../referencia/core/axiz-core-metrics.md)
- [`axiz-core-money`](../referencia/core/axiz-core-money.md)
