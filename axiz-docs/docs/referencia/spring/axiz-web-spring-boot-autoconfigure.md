<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-web-spring-boot-autoconfigure

Correlación, validación del catálogo de cabeceras, paginación, versionado de API, CORS y metadatos OpenAPI (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-web-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizWebAutoConfiguration` | Convenciones web para aplicaciones servlet y reactivas: contexto de la petición, catálogo de cabeceras, paginación, CORS, versionado de API y personalización de JSON (el mapper de Boot se personaliza, nunca se reemplaza). |
| `AxizWebEnvironmentPostProcessor` | Valores web seguros por defecto con la precedencia más baja: hilos virtuales, cierre ordenado, sin stack traces ni mensajes de excepción en las respuestas de error, los problem details propios de Boot desactivados (el starter de errores es su dueño) y metadatos OpenAPI tomados del nombre de la aplicación. |
| `AxizWebFluxConfigurer` | Cableado de WebFlux: parámetros de paginación, CORS y versionado nativo de API. |
| `AxizWebMvcConfigurer` | Cableado de Spring MVC: parámetros de paginación, CORS y versionado nativo de API. |
| `AxizWebProperties` | Convenciones web (`axiz.web.*`). |
| `MdcKeys` | Claves MDC escritas por la capa web; el starter de logging las emite como campos JSON. |
| `PageRequestArgumentResolver` | Resolvedor de Spring MVC para parámetros `PageRequest` de los controladores. |
| `PageRequestParser` | Interpreta los parámetros de consulta `page`, `size` y `sort`. |
| `Paging` | Reglas de paginación de un parámetro `PageRequest` de un controlador. |
| `ReactivePageRequestArgumentResolver` | Resolvedor de WebFlux para parámetros `PageRequest` de los controladores. |
| `RequestContextAccessor` | Conecta `RequestContext` con context-propagation de Micrometer, para que los metadatos sigan a los operadores de Reactor, los executors y los hilos virtuales. |
| `RequestMetadataFilter` | Filtro servlet que abre el contexto de la petición: ids de correlación y de petición (reutilizados solo cuando es seguro), validación de las cabeceras del catálogo, entradas MDC y la cabecera de respuesta `X-Correlation-Id`. |
| `RequestMetadataResolver` | Construye `RequestMetadata` a partir de una petición entrante y valida el catálogo de cabeceras. |
| `RequestMetadataWebFilter` | Equivalente reactivo de `RequestMetadataFilter`. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-web-spring-boot-autoconfigure verify`.

## Límites del cuerpo de la petición y de JSON

`axiz.web.max-request-body` vale `1MB` (1 MiB) por defecto. Las reglas ordenadas en `axiz.web.body-limits`
definen `path` y `max-size` para rutas de carga o streaming; gana la primera regla que coincide. Un
Content-Length declarado que supera el límite de la ruta se rechaza de forma temprana. Ambas pilas también
cuentan los bytes durante la lectura, incluidas las peticiones chunked, y responden con un Problem 413.

WebFlux libera los buffers rechazados y configura la agregación de los codecs al mayor límite de cuerpo
declarado; el contador por ruta aplica los límites menores de cada ruta. Servlet no almacena toda la
petición en buffer. El código que confirma una respuesta de streaming antes de consumir una petición
demasiado grande no puede reemplazar esa respuesta ya confirmada por un Problem; cuando ese contrato de
respuesta importa, la petición se consume y valida antes de confirmar una respuesta exitosa.

El `JsonMapper` efectivo de Boot recibe restricciones de stream explícitas de `axiz.web.json`:
`max-number-length=100`, `max-string-length=1048576`, `max-nesting-depth=64`. Todos los valores deben ser
positivos. El mapper se personaliza mediante el factory builder de Boot en lugar de reemplazarse, lo que
conserva los demás módulos del framework y de la aplicación.

`axiz.web.json.reject-unknown-properties` (por defecto `true`) hace que el servicio rechace un cuerpo de
petición con una propiedad que su tipo no declara (400) en lugar de ignorarla, de modo que un cliente no
puede asignar campos que el contrato no expone. Los clientes HTTP creados por el framework y los
consumidores de eventos leen los payloads de terceros de forma tolerante y aceptan campos agregados.
