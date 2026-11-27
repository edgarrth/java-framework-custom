<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-security-spring-boot-autoconfigure

Resource server OAuth2 con lista de emisores permitidos, mapeo de claims, seguridad de métodos, cabeceras y límite de tasa (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-security-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AudienceValidator` | Acepta un token solo cuando una de sus audiencias está configurada para su emisor. |
| `AxizAuthentication` | Autenticación respaldada por un JWT validado cuyo principal es un `AxizPrincipal`, de modo que los controladores pueden declarar `@AuthenticationPrincipal AxizPrincipal caller`. |
| `AxizJwtConverter` | Mapea un JWT validado a un `AxizAuthentication` con autoridades de scope y de rol. |
| `AxizSecurityAutoConfiguration` | Resource server OAuth2 para servicios servlet y reactivos: lista de emisores permitidos con JWKS por emisor, comprobación de audiencia y algoritmo, claims mapeados a `AxizPrincipal`, seguridad de métodos, cabeceras de seguridad, actuator protegido, respuestas de problema para 401/403/429 y límites de tasa opcionales por llamador. |
| `AxizSecurityProperties` | Configuración del resource server (`axiz.security.*`). |
| `CallerContextFilter` | Agrega el llamador autenticado (cliente, sujeto, tenant) al contexto de la petición después de validar el token, para que lo vean los logs, la auditoría, las métricas y las llamadas salientes. |
| `CallerContextWebFilter` | Equivalente reactivo de `CallerContextFilter`: enriquece el contexto de Reactor. |
| `ProblemAuthenticationEntryPoint` | Manejo servlet de 401 y 403: cuerpo de problema más `WWW-Authenticate: Bearer`. |
| `RateLimitFilter` | Límite de tasa por llamador después de la autenticación. |
| `RateLimitRules` | Resuelve la regla y la clave de límite de tasa de una petición; la clave siempre proviene del token. |
| `RateLimitWebFilter` | Límite de tasa reactivo; la llamada al limitador se ejecuta fuera del event loop. |
| `ReactiveProblemHandlers` | Manejo reactivo de 401 y 403 con cuerpos de problema. |
| `SecurityProblems` | Representa las respuestas 401, 403 y 429 como problemas RFC 9457 con códigos del catálogo. |
| `TrustedIssuerDecoders` | Un decoder por emisor confiable, creado de forma diferida y reutilizado. |

Tipos relacionados: `AxizSecurityRedisAutoConfiguration` están en `axiz-security-redis-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-security-spring-boot-autoconfigure verify`.

## Cobertura efectiva de autorización

La cobertura clasifica estructuralmente la expresión `@PreAuthorize` efectiva, con el mismo escáner de
anotaciones que Spring Security (incluidas las plantillas de anotaciones). El arranque nunca evalúa SpEL
ni llama a beans de políticas de la aplicación. Las constantes y los predicados de autenticación por sí
solos no establecen una barrera de permisos. Para declarar un endpoint que solo requiere autenticación no
anónima se usa `pe.axiz.core.security.AuthenticatedAccess`; Axiz instala un interceptor previo en ambas
pilas. Acepta JWTs autenticados sin autoridades y la autenticación remember-me, y rechaza el acceso
anónimo. No se puede combinar con `@PublicEndpoint`, una ruta pública ni una expresión siempre verdadera.

La gramática admitida incluye literales booleanos, `and`, `or`, `not`, `permitAll()`, `denyAll()`, los
cuatro predicados de autenticación, comprobaciones literales de rol o autoridad, `hasPermission(...)` y
políticas `@bean.method(...)` con argumentos literales, de principal, de autenticación o de variable o
propiedad. Las comparaciones, constructores, referencias de tipo, indexaciones, asignaciones y cadenas
arbitrarias de métodos hacen fallar el arranque incluso en el modo `warn` de desarrollo. Las
comprobaciones negativas de autoridad son exclusiones y por sí solas no prueban la cobertura de permisos.
Las políticas dinámicas son responsabilidad de la aplicación y requieren pruebas de autorización
negativas. Los atributos de `@Secured` son autoridades literales, no tokens de un voter.

Las pruebas de integración HTTP incluyen los handlers de error: las denegaciones de métodos sin
autenticación devuelven 401 en ambas pilas; los llamadores autenticados sin los permisos requeridos
reciben 403. Una regla HTTP explícita efectiva o una declaración pública sigue siendo una fuente de
cobertura independiente.

Cuando la aplicación queda lista, cada endpoint de negocio MVC/WebFlux necesita una regla de autorización
previa. El framework comprueba el proxy y el pointcut propios del bean mapeado: no cuentan una anotación
ni un advisor global que no estén vinculados a ese proxy. `@PostAuthorize` por sí solo no cuenta. Se
admiten `@PreAuthorize`, `@Secured`/`@RolesAllowed` habilitados y las reglas específicas compiladas desde
`axiz.security.authorization.rules`. Las autoridades requeridas dentro de una regla se combinan con AND.
El `anyRequest().authenticated()` por defecto no declara permisos del endpoint.

Un `@PublicEndpoint` requiere además una ruta permit-all efectiva y coincidente en
`axiz.security.public-paths`. Los endpoints GET también necesitan autorización de su ruta HEAD implícita.
`axiz.security.authorization.unprotected=fail` es el valor por defecto; `warn` es solo para desarrollo.
Los mappings de infraestructura de Spring Boot y springdoc se excluyen del escaneo de cobertura de negocio
y conservan sus propias políticas de seguridad HTTP configuradas.

Las cadenas de filtros personalizadas pueden contener authorization managers arbitrarios; el framework no
puede certificar su estado privado de DSL a partir de las declaraciones de configuración. Con una cadena
personalizada se declara una autorización de método previa y efectiva en los endpoints de negocio. La
cobertura HTTP basada en propiedades solo se considera confiable cuando la cadena instalada realmente es
la cadena del framework. Las pruebas deben verificar las denegaciones antes de cualquier invocación de la
aplicación, además de la cobertura al arranque.

En CQRS, `axiz.cqrs.authorization.unprotected` también vale `fail` por defecto. La cobertura del bus es una
comprobación de declaraciones; los permisos sobre un recurso particular son responsabilidad del servicio.

## Política ante caídas del límite de tasa

Cada regla debe declarar explícitamente `axiz.security.rate-limit.rules[].fail-open` cuando el límite de
tasa está habilitado; no existe una política global ante caídas. Una regla sin política hace fallar el
arranque. `false` devuelve un Problem 503 si el almacén no está disponible; `true` permite la petición.
Superar la cuota de un almacén sano sigue devolviendo 429. El framework nunca interpreta una caída como
agotamiento de la cuota.

### Renderizado reactivo de problemas

Los resolvedores de mensajes síncronos se ejecutan en `axizSecurityRenderingScheduler`, propiedad del
contexto: cuatro workers y 64 tareas encoladas por worker, aislados del pool compartido de Reactor. Este
bean con nombre se reemplaza cuando hay requisitos de capacidad medidos; Spring lo libera al cierre. Estos
schedulers internos no son candidatos de autowiring por defecto. La construcción manual requiere un
scheduler explícito propio de la aplicación. La saturación devuelve un HTTP 503 RFC 9457 estático con
`no-store` y un `Retry-After` de al menos un segundo, conservando una pausa mayor de cuota o de emisor ya
fijada en la respuesta, sin invocar al resolvedor en el event loop. El respaldo 503 elimina
`WWW-Authenticate`, porque la saturación no es una decisión de autenticación ni de autorización.

`ReactiveRenderContext` toma los metadatos de la petición de Reactor, luego del exchange, y la
Observation actual de la clave de observación de Reactor. Nunca importa identidad ni IDs de traza del hilo
suscriptor ni de un contexto residual del worker. Limpia el MDC, abre el scope de esa observación, instala
IDs de correlación y de petición validados para el renderizado y restaura el estado previo del worker en
`finally`, incluidos los fallos del resolvedor. La escritura de la respuesta HTTP sigue al scope de renderizado.
