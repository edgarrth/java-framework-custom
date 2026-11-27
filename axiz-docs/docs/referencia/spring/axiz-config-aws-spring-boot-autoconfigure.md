<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-config-aws-spring-boot-autoconfigure

Bootstrap de AWS Parameter Store con credenciales propias y selectores inmutables.

Este módulo es dueño de la API `pe.axiz.spring.config.aws`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-config-aws-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AwsBootstrapLifecycle`
- `AxizConfigAwsAutoConfiguration`
- `AxizConfigAwsEnvironmentPostProcessor`
- `AxizConfigAwsProperties`
- `AxizConfigAwsValidationEnvironmentPostProcessor`
- `AxizParameterStoreLoader`
- `AxizParameterStoreResolver`
- `AxizParameterStoreResource`

## Configuración

Prefijos de propiedades propios: `axiz.config.parameter-store.*`.

## Uso desde el catálogo

```bash
axiz add config-aws
```

Capacidades requeridas: `config`.

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-config-aws-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
