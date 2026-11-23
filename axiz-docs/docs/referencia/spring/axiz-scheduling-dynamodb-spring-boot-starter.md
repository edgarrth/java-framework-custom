<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-scheduling-dynamodb-spring-boot-starter

Scheduling opcional con DynamoDB.

Este módulo es dueño de la API `pe.axiz.spring.scheduling.dynamodb.starter`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-scheduling-dynamodb-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-scheduling-dynamodb-spring-boot-autoconfigure`](axiz-scheduling-dynamodb-spring-boot-autoconfigure.md).

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.
