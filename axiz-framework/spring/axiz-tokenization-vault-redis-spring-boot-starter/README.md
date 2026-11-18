# axiz-tokenization-vault-redis-spring-boot-starter

Starter sin código para seleccionar Redis/Valkey como presupuesto compartido de la bóveda.
Requiere límites explícitos, credenciales y alarma de conciliación; no configura caché ni el
rate limiter general de la aplicación. Consultar
[configuración y pruebas](../axiz-tokenization-vault-redis-spring-boot-autoconfigure/README.md).

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-tokenization-vault-redis-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-tokenization-vault-redis-spring-boot-autoconfigure`](../axiz-tokenization-vault-redis-spring-boot-autoconfigure/README.md).

## Catalog usage

```bash
axiz add tokenization-vault-redis
```

Required capabilities: `tokenization-vault`, `secrets`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
axiz.tokenization.vault.redis.fail-open: false
axiz.tokenization.vault.redis.connection.tls: true
```

## Operations

Configure an authenticated TLS Redis/Valkey endpoint with bounded connection and command
timeouts. Size capacity and eviction according to the capability: coordination/idempotency
state cannot use cache eviction semantics. In-memory substitutes are limited to explicitly
local/test/ci environments.

See [tokenization](../../../axiz-docs/docs/guias/tokenizacion.md) for client/vault boundaries,
profiles, authorization, retention and the independent field-protection flow.
