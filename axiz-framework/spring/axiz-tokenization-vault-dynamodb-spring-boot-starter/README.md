# axiz-tokenization-vault-dynamodb-spring-boot-starter

Starter sin código que incorpora la integración DynamoDB de la bóveda central.
Incluye el adaptador y soporte AWS; se instala solo en el servicio propietario de la bóveda.
Requiere infraestructura y colaboradores explícitos. Configuración, permisos y pruebas en
[la autoconfiguración](../axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure/README.md).

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-tokenization-vault-dynamodb-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure`](../axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure/README.md).

## Catalog usage

```bash
axiz add tokenization-vault-dynamodb
```

Required capabilities: `tokenization-vault`, `dynamodb`, `crypto-kms`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
axiz.tokenization.vault.dynamodb.table: ${TOKEN_VAULT_TABLE}
```

## Operations

Provide region, workload identity, TLS and least-privilege permissions for the selected AWS
resources. Provision production tables, queues, topics, keys and buckets through reviewed IaC.
LocalStack tests exercise behavior but do not attest deployed IAM or service durability.
Never place long-lived credentials in generated YAML.

See [tokenization](../../../axiz-docs/docs/guias/tokenizacion.md) for client/vault boundaries,
profiles, authorization, retention and the independent field-protection flow.
