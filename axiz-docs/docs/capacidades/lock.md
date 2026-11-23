<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# lock

Locks distribuidos (en memoria solo en local/test; los ambientes compartidos requieren lock-redis o lock-dynamodb)

```bash
axiz add lock
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-lock-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | ningún arquetipo |

Perfil `local` (`application-local.yaml`):

```yaml
axiz.lock.store: memory
```

## Uso

```java
// el lease se comprueba justo antes del efecto; el débito es idempotente (clave) en el core
lockTemplate.withLock("account:" + id, Duration.ofSeconds(10), Duration.ofSeconds(2), lease -> {
  lease.ensureHeld();
  return core.debit(id, amount, idempotencyKey);
});
```

## Módulos

- [`axiz-lock-spring-boot-autoconfigure`](../referencia/spring/axiz-lock-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-lock`](../referencia/core/axiz-core-lock.md)
