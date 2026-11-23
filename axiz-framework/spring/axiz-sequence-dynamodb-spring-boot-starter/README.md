# axiz-sequence-dynamodb-spring-boot-starter

Secuencias opcionales con DynamoDB.

Este módulo es dueño de la API `pe.axiz.spring.sequence.dynamodb.starter`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-sequence-dynamodb-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-sequence-dynamodb-spring-boot-autoconfigure`](../axiz-sequence-dynamodb-spring-boot-autoconfigure/README.md).

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.
