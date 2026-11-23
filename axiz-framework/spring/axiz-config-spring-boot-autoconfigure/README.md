<!-- generated -->
# axiz-config-spring-boot-autoconfigure

ConfigMaps montados como archivos y convenciones de Parameter Store con validación temprana (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-config-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizConfigAutoConfiguration` | Validación al arranque de la configuración externalizada. |
| `AxizConfigEnvironmentPostProcessor` | Aplica las convenciones de configuración antes de cargar los datos de configuración. |
| `AxizConfigProperties` | Convenciones de configuración (`axiz.config.*`). |
| `RequiredPropertiesVerifier` | Hace fallar el arranque cuando una propiedad declarada en `axiz.config.required` falta o está vacía. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-config-spring-boot-autoconfigure verify`.

## Bootstrap gestionado de Parameter Store

El resolvedor `aws-parameterstore:` selecciona credenciales gestionadas antes de la primera lectura remota,
rechaza configuraciones `spring.cloud.aws.credentials.*` en competencia y conserva los proveedores y
clientes de bootstrap registrados explícitamente. Se permite `instance-profile=false`. Los recursos creados
por el bootstrap se liberan ante un fallo temprano o se transfieren al cierre de la aplicación; los
recursos prestados siguen siendo de la extensión. La integración sigue siendo opcional cuando faltan las
librerías de AWS.
