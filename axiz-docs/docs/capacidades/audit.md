<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# audit

Rastro de auditoría de negocio con @Auditable, saneado por sensibilidad, con entrega ASYNC/DURABLE/SYNC explícita y cadena HMAC con clave fuera de local/test/ci

```bash
axiz add audit
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-audit-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, batch |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.audit.mode: ASYNC
```

## Secretos

Llaves del Secret que sincroniza el ExternalSecret desde AWS Secrets Manager:

- `audit-hmac-key`

## Uso

```java
@Auditable(action = "create", resource = "order", resourceIdArgument = "id")
```

## Módulos

- [`axiz-audit-spring-boot-autoconfigure`](../referencia/spring/axiz-audit-spring-boot-autoconfigure.md)
- [`axiz-core-audit`](../referencia/core/axiz-core-audit.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-id`](../referencia/core/axiz-core-id.md)
- [`axiz-core-json`](../referencia/core/axiz-core-json.md)
- [`axiz-core-messaging`](../referencia/core/axiz-core-messaging.md)
- [`axiz-core-outbox`](../referencia/core/axiz-core-outbox.md)
- [`axiz-core-secrets`](../referencia/core/axiz-core-secrets.md)
- [`axiz-core-sensitive`](../referencia/core/axiz-core-sensitive.md)
