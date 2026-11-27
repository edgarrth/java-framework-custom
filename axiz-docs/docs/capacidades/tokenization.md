<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# tokenization

Cliente remoto de tokens autenticado, sin almacenamiento de bóveda, claves ni SDK de proveedores

```bash
axiz add tokenization
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-tokenization-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`http-client`](http-client.md), [`security`](security.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.tokenization.mode: remote
axiz.tokenization.remote.client: token-vault
axiz.http-client.clients.token-vault.base-url: ${TOKEN_VAULT_URL}
axiz.http-client.clients.token-vault.auth.type: TOKEN_RELAY
axiz.http-client.clients.token-vault.retry: NONE
axiz.http-client.clients.token-vault.max-attempts: 1
axiz.http-client.clients.token-vault.max-concurrent-calls: 32
axiz.http-client.clients.token-vault.max-connections: 32
axiz.http-client.clients.token-vault.connect-timeout: 2s
axiz.http-client.clients.token-vault.read-timeout: 5s
axiz.http-client.clients.token-vault.total-timeout: 10s
```

Perfil `local` (`application-local.yaml`):

```yaml
axiz.tokenization.mode: local
axiz.http-client.clients.token-vault.base-url: https://synthetic.invalid
```

## Llaves del ConfigMap por ambiente

- `axiz.http-client.clients.token-vault.base-url`

## Uso

```java
// SensitiveValue se posee y se cierra; el consumidor recibe solo el token, nunca el índice.
tokenizer.tokenize(type, value, domain, operationId);
```

## Módulos

- [`axiz-tokenization-spring-boot-autoconfigure`](../referencia/spring/axiz-tokenization-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-http`](../referencia/core/axiz-core-http.md)
- [`axiz-core-json`](../referencia/core/axiz-core-json.md)
- [`axiz-core-tokenization`](../referencia/core/axiz-core-tokenization.md)
