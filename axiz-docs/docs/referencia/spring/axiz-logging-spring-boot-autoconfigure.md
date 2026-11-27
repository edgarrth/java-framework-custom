<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-logging-spring-boot-autoconfigure

Logs JSON estructurados, registro de operaciones con enmascaramiento y logs de peticiones HTTP (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-logging-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizLoggingAutoConfiguration` | `LogOperation` mediante un advisor de infraestructura (compatible con AOT y nativo, sin AspectJ) y el log de acceso HTTP para aplicaciones servlet y reactivas. |
| `AxizLoggingEnvironmentPostProcessor` | Valores por defecto del logging estructurado, aplicados antes de que arranque el sistema de logging. |
| `AxizLoggingProperties` | Convenciones de logging (`axiz.logging.*`). |
| `FailureKind` | Distingue los rechazos de negocio esperados (registrados en WARN) de los fallos (registrados en ERROR). |
| `HttpLoggingFilter` | Una línea de log de acceso por petición (logger `axiz.http`) con método, ruta, estado, duración y cabeceras permitidas. |
| `HttpLoggingWebFilter` | Log de acceso reactivo: la misma línea que el filtro servlet, sin cuerpos. |
| `LogOperationInterceptor` | Aplica `LogOperation`: líneas de entrada y salida con duración y resultado. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-logging-spring-boot-autoconfigure verify`.
