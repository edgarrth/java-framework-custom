# axiz-jpa-aws-spring-boot-starter

Wrapper JDBC de AWS y credenciales IAM opcionales.

Este módulo es dueño de la API `pe.axiz.spring.jpa.aws.starter`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-jpa-aws-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-jpa-aws-spring-boot-autoconfigure`](../axiz-jpa-aws-spring-boot-autoconfigure/README.md).

## Uso desde el catálogo

```bash
axiz add jpa-aws
```

Capacidades requeridas: `jpa`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.jpa.aws-wrapper.enabled: ${AXIZ_JPA_AWS_WRAPPER:true}
axiz.jpa.aws-wrapper.iam: ${AXIZ_JPA_IAM:false}
```

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.
