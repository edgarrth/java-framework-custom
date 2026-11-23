<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# crypto

Cifrado de campos, índices ciegos con rotación independiente y hash de contraseñas

```bash
axiz add crypto
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-crypto-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`secrets-file`](secrets-file.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.crypto.fields.current-key-id: v1
axiz.crypto.fields.key-ids:
- v1
axiz.crypto.fields.index-current-key-id: v1
axiz.crypto.fields.index-secrets.v1: blind-index-key
```

## Llaves del ConfigMap por ambiente

- `axiz.crypto.fields.current-key-id`
- `axiz.crypto.fields.key-ids`

## Secretos

Llaves del Secret que sincroniza el ExternalSecret desde AWS Secrets Manager:

- `field-key-v1`
- `blind-index-key`

## Uso

```java
@Converter
final class AccountNumberConverter extends EncryptedConverter {
  AccountNumberConverter(FieldEncryptor encryptor) { super(encryptor); }
  @Override protected String context() { return "account.number"; }
}
@Convert(converter = AccountNumberConverter.class) String accountNumber;
```

## Módulos

- [`axiz-crypto-spring-boot-autoconfigure`](../referencia/spring/axiz-crypto-spring-boot-autoconfigure.md)
- [`axiz-core-crypto`](../referencia/core/axiz-core-crypto.md)
- [`axiz-core-secrets`](../referencia/core/axiz-core-secrets.md)
