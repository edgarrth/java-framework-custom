<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-storage-s3-spring-boot-autoconfigure

Integración con Amazon S3 para almacenamiento de objetos.

Este módulo es dueño de la API `pe.axiz.spring.storage.s3`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-storage-s3-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizStorageS3AutoConfiguration`
- `AxizStorageS3Properties`

## Configuración

Prefijos de propiedades propios: `axiz.storage.*`.

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

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-storage-s3-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
