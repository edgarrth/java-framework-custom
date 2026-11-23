<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# tokenization-vault-redis

Conexión TLS autenticada y dedicada para presupuestos de tokens distribuidos que fallan de forma cerrada

```bash
axiz add tokenization-vault-redis
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-tokenization-vault-redis-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`tokenization-vault`](tokenization-vault.md), [`secrets`](secrets.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.tokenization.vault.redis.fail-open: false
axiz.tokenization.vault.redis.connection.tls: true
```

## Llaves del ConfigMap por ambiente

- `axiz.tokenization.vault.redis.namespace`
- `axiz.tokenization.vault.redis.connection`
- `axiz.tokenization.vault.redis.policy`

## Uso

```java
# Se aportan todas las ventanas de sujeto, cliente y tenant y los límites de concurrencia; no hay cuotas inventadas.
# connection.password-secret es una referencia de SecretResolver, nunca una credencial en línea.
```

## Módulos

- [`axiz-tokenization-vault-redis-spring-boot-autoconfigure`](../referencia/spring/axiz-tokenization-vault-redis-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-secrets`](../referencia/core/axiz-core-secrets.md)
- [`axiz-core-tokenization-vault`](../referencia/core/axiz-core-tokenization-vault.md)
