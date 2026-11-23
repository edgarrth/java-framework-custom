<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-crypto-kms-spring-boot-starter

Cifrado de sobre con AWS KMS.

Este módulo es dueño de la API `pe.axiz.spring.crypto.kms.starter`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-crypto-kms-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-crypto-kms-spring-boot-autoconfigure`](axiz-crypto-kms-spring-boot-autoconfigure.md).

## Uso desde el catálogo

```bash
axiz add crypto-kms
```

Capacidades requeridas: `crypto`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.crypto.kms.key-id: ${AXIZ_KMS_KEY_ID:alias/local}
```

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.
