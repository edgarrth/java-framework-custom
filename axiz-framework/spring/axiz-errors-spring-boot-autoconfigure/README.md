<!-- generated -->
# axiz-errors-spring-boot-autoconfigure

Respuestas de error RFC 9457 con el catálogo de errores externalizado para las pilas servlet y reactiva (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-errors-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizErrorsAutoConfiguration` | Respuestas de error RFC 9457 para aplicaciones servlet y reactivas, respaldadas por el catálogo de errores. |
| `AxizErrorsProperties` | Configuración del manejo de errores (`axiz.errors.*`). |
| `ProblemControllerAdvice` | Convierte toda excepción lanzada por los handlers MVC en una respuesta RFC 9457. |
| `ProblemErrorController` | Reemplaza el controlador de error whitelabel de Boot: los errores lanzados en filtros o por el contenedor también se representan como problemas RFC 9457. |
| `ProblemResponder` | Convierte fallos en problemas y registra metadatos de diagnóstico acotados: errores del servidor en ERROR, dependencias fallidas y acceso denegado en WARN, conflictos en INFO y otros errores del cliente en DEBUG. Nunca registra mensajes de excepción, objetos throwable ni rutas de la petición. |
| `ProblemWebExceptionHandler` | Handler de excepciones WebFlux que produce problemas RFC 9457 (se ejecuta antes del handler por defecto de Boot). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-errors-spring-boot-autoconfigure verify`.

### Renderizado reactivo de problemas

Los resolvedores de mensajes síncronos se ejecutan en `axizErrorRenderingScheduler`, propiedad del
contexto: cuatro workers y 64 tareas encoladas por worker, aislados del pool compartido de Reactor. Este
bean con nombre se reemplaza cuando hay requisitos de capacidad medidos; Spring lo libera al cierre. Estos
schedulers internos no son candidatos de autowiring por defecto. La construcción manual requiere un
scheduler explícito propio de la aplicación. La saturación devuelve un HTTP 503 RFC 9457 estático con
`no-store` y un `Retry-After` de al menos un segundo, conservando el retraso acotado de un
`RateLimitedException` tipado, incluidos los fallos envueltos. El respaldo nunca invoca al resolvedor
en el event loop.

`ReactiveRenderContext` toma los metadatos de la petición de Reactor, luego del exchange, y la
Observation actual de la clave de observación de Reactor. Nunca importa identidad ni IDs de traza del hilo
suscriptor ni de un contexto residual del worker. Limpia el MDC, abre el scope de esa observación, instala
IDs de correlación y de petición validados para el renderizado y restaura el estado previo del worker en
`finally`, incluidos los fallos del resolvedor. La escritura de la respuesta HTTP sigue al scope de renderizado.
