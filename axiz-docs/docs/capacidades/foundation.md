<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# foundation

Reloj y calendario de negocio (zona, fines de semana, feriados), valores por defecto de JSON y validación

```bash
axiz add foundation
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-foundation-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, ux, support, event-processor, batch |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.foundation.zone: America/Lima
```

## Llaves del ConfigMap por ambiente

- `axiz.foundation.calendar.holidays`

## Uso

```java
BusinessCalendar calendar; // inyectado
LocalDate settlement = calendar.addBusinessDays(today, 1);
```

## Módulos

- [`axiz-foundation-spring-boot-autoconfigure`](../referencia/spring/axiz-foundation-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-id`](../referencia/core/axiz-core-id.md)
- [`axiz-core-identifiers`](../referencia/core/axiz-core-identifiers.md)
- [`axiz-core-money`](../referencia/core/axiz-core-money.md)
- [`axiz-core-time`](../referencia/core/axiz-core-time.md)
