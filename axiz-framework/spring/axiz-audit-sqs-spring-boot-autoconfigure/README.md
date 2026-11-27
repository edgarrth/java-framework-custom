# axiz-audit-sqs-spring-boot-autoconfigure

Destino de auditoría SQS con publicación nativa síncrona y reactiva.

Este módulo es dueño de la API `pe.axiz.spring.audit.sqs`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-audit-sqs-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizAuditSqsAutoConfiguration`
- `AxizAuditSqsProperties`

## Configuración

Prefijos de propiedades propios: `axiz.audit.sqs.*`.

## Uso desde el catálogo

```bash
axiz add audit-sqs
```

Capacidades requeridas: `audit`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.audit.sqs.queue-url: ${AXIZ_AUDIT_QUEUE_URL}
```

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-audit-sqs-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
