# axiz-tokenization-vault-dynamodb-spring-boot-starter

Starter sin código que incorpora la integración DynamoDB de la bóveda central.
Incluye el adaptador y soporte AWS; se instala solo en el servicio propietario de la bóveda.
Requiere infraestructura y colaboradores explícitos. Configuración, permisos y pruebas en
[la autoconfiguración](../axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure/README.md).

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-tokenization-vault-dynamodb-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure`](../axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure/README.md).

## Uso desde el catálogo

```bash
axiz add tokenization-vault-dynamodb
```

Capacidades requeridas: `tokenization-vault`, `dynamodb`, `crypto-kms`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.tokenization.vault.dynamodb.table: ${TOKEN_VAULT_TABLE}
```

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.

La [guía de tokenización](../../../axiz-docs/docs/guias/tokenizacion.md) describe los límites entre
cliente y bóveda, los perfiles, la autorización, la retención y el flujo independiente de protección
de campos.
