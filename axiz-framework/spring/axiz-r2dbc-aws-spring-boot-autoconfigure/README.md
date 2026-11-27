# axiz-r2dbc-aws-spring-boot-autoconfigure

Credenciales IAM de AWS para los pools R2DBC de escritura y lectura.

Este módulo es dueño de la API `pe.axiz.spring.r2dbc.aws`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-r2dbc-aws-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizR2dbcAwsEnvironmentPostProcessor`
- `AxizR2dbcAwsProperties`
- `AxizR2dbcIamAutoConfiguration`

## Configuración

Prefijos de propiedades propios: `axiz.r2dbc.iam.*`.

## Uso desde el catálogo

```bash
axiz add r2dbc-aws
```

Capacidades requeridas: `r2dbc`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.r2dbc.iam.enabled: ${AXIZ_R2DBC_IAM:true}
```

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-r2dbc-aws-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
