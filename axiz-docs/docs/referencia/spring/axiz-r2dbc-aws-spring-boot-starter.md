<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-r2dbc-aws-spring-boot-starter

Autenticación IAM de AWS opcional para R2DBC.

Este módulo es dueño de la API `pe.axiz.spring.r2dbc.aws.starter`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-r2dbc-aws-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-r2dbc-aws-spring-boot-autoconfigure`](axiz-r2dbc-aws-spring-boot-autoconfigure.md).

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
