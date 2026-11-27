<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-storage-s3-spring-boot-starter

Almacenamiento de objetos en S3 con URLs firmadas acotadas.

Este módulo es dueño de la API `pe.axiz.spring.storage.s3.starter`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-storage-s3-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-storage-s3-spring-boot-autoconfigure`](axiz-storage-s3-spring-boot-autoconfigure.md).

## Uso desde el catálogo

```bash
axiz add storage-s3
```

Capacidades requeridas: `storage`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.storage.bucket: ${AXIZ_STORAGE_BUCKET:local-bucket}
```

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.
