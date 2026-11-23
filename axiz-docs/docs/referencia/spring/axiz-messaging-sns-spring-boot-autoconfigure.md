<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-messaging-sns-spring-boot-autoconfigure

Integración SNS para mensajería CloudEvents.

Este módulo es dueño de la API `pe.axiz.spring.messaging.sns`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-sns-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizMessagingSnsAutoConfiguration`
- `AxizMessagingSnsClientProperties`
- `AxizNativeMessagingSnsAutoConfiguration`

## Configuración

Prefijos de propiedades propios: `axiz.messaging.aws.*`.

## Uso desde el catálogo

```bash
axiz add messaging-sns
```

Capacidades requeridas: `messaging`.

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-messaging-sns-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
