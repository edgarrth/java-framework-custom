# Métricas por evento

El framework no define métricas por tipo de negocio: expone **siete instrumentos genéricos** y el
tipo de evento llega como el tag `event`. Un servicio mide logins, pagos o cualquier evento nuevo
sin modificar la librería, y un solo dashboard filtra por evento
([capas y ejecución](../arquitectura/capas.md)).

| Operación | Instrumento (OTel) | En Prometheus | Para qué |
|---|---|---|---|
| Contar | `axiz.events` | `axiz_events_total` | ocurrencias: logins, pagos, bloqueos, OTP |
| Sumar montos | `axiz.events.amount` | `axiz_events_amount_sum/_count/_max/_bucket` | monto total, ticket promedio y máximo, percentiles |
| Sumar valores | `axiz.events.value` | `axiz_events_value_*` | registros de un archivo, ítems, intentos |
| Medir duración | `axiz.events.duration` | `axiz_events_duration_seconds_*` | latencia de negocio, percentiles, SLO |
| Nivel actual | `axiz.events.active` | `axiz_events_active` | sesiones activas, operaciones en curso |
| Procesos en curso | `axiz.events.inflight` | `axiz_events_inflight_*` | conciliaciones, lotes, sagas |
| Valor observado | `axiz.events.gauge` | `axiz_events_gauge` | tamaño de una cola, cupo disponible |

Promedios, tasas, ratios y embudos no son instrumentos: se calculan en Grafana.

## Uso

```java
metrics.record(MetricEvent.of("payment").channel("mobile").amount(order.amount()).outcome(Outcome.SUCCESS));
metrics.count("login", e -> e.outcome(Outcome.REJECTED).reason("invalid-otp"));
metrics.value("file.load", 12_480, e -> e.outcome(Outcome.SUCCESS));

var sample = metrics.start("transfer.external");
// ...
sample.stop(Outcome.SUCCESS);

metrics.active("session").increment();
metrics.gauge("queue.pending", pendingQueue, Queue::size);

@Metered(event = "payment")   // cuenta y mide; deriva outcome y reason de la excepción
public PaymentResult pay(PaymentCommand command) { ... }   // un resultado MetricSource aporta monto y tags
```

## Tags

| Tag | Obligatorio | Valores |
|---|---|---|
| `event` | sí | nombre del evento en minúsculas con `.`, `-` o `_` |
| `outcome` | en conteo y duración | `success`, `rejected`, `error`, `timeout`, `cancelled` |
| `reason` | no | código del catálogo de errores |
| `category`, `product`, `stage` | no | catálogos del servicio |
| `channel` | no | `web`, `mobile`, `branch`, `atm`, `pos`, `partner`, `batch`, `internal` (configurable) |
| `currency` | solo en montos | ISO 4217 |
| `tenant` | solo multi-tenant | |
| propios | no | declarados en `axiz.metrics.tags` |
| `service`, `env`, `version`, `domain` | automáticos | los pone el framework |

## Cardinalidad

Límite de eventos distintos por servicio (`axiz.metrics.max-events`, 200) y de valores por tag
(`max-values-per-tag`, 50): el exceso se agrupa como `other` y se registra un aviso. Un tag no
declarado se rechaza. **Nunca** ids de cliente, cuenta, tarjeta o transacción, montos ni texto
libre como tags: eso va en trazas y logs.

## Catálogo opcional

```yaml
axiz:
  metrics:
    events:
      payment:
        category: payment
        duration-slos: [200ms, 500ms, 1s, 2s]
        amount-slos: [100, 500, 1000, 5000]
      file.load:
        category: operation
        value-unit: records
    tags:
      card-brand:
        allowed: [visa, mastercard, amex]
```

## Catálogo sugerido para banca

| Categoría | Eventos |
|---|---|
| security | `login`, `logout`, `mfa.challenge`, `otp.send`, `otp.validate`, `password.reset`, `device.enroll`, `session` |
| customer | `onboarding` (con `stage`), `kyc.validate`, `profile.update`, `consent.update` |
| account | `account.open`, `account.close`, `balance.inquiry`, `statement.download` |
| payment | `payment`, `transfer.internal`, `transfer.external`, `bill.payment`, `qr.payment`, `refund`, `reversal` |
| card | `card.issue`, `card.activate`, `card.block`, `card.limit.change`, `card.authorization`, `pin.change` |
| credit | `loan.application`, `loan.disbursement`, `installment.payment`, `credit.score` |
| risk | `fraud.check`, `fraud.alert`, `aml.screening` |
| notification | `notification.send` (con un tag propio, p. ej. `medium`: email, sms, push; `channel` es el canal del cliente) |
| operation | `file.load`, `batch.run`, `reconciliation.mismatch`, `queue.pending` |

Las métricas son operativas: las cifras contables salen del registro transaccional. Para totales
mensuales Prometheus debe retener al menos 35 días. Dashboards en
[Observabilidad](../operacion/observabilidad.md).
