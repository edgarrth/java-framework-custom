<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-http-client-spring-boot-autoconfigure

Clientes declarativos @HttpExchange con configuración por cliente, autenticación saliente, resiliencia, traducción de errores y logs (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-http-client-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizExchangeFilter` | Equivalente reactivo de `AxizRestClientInterceptor`, sin bloquear: las credenciales usan transportes asíncronos nativos de secretos y tokens, con la cancelación propagada a la I/O; los errores se traducen desde el cuerpo de la respuesta y los reintentos usan el backoff de Reactor solo para llamadas idempotentes. |
| `AxizHttpClientAutoConfiguration` | Clientes HTTP declarativos (`@HttpExchange`) sobre los grupos de servicios HTTP de Spring: cada grupo cuyo nombre coincide con `axiz.http-client.clients.<name>` recibe el transporte axiz, timeouts, credenciales, resiliencia, propagación, traducción de errores y logging. |
| `AxizHttpClientProperties` | Clientes HTTP lógicos (`axiz.http-client.clients.<name>`). |
| `AxizHttpClients` | Aplica la configuración axiz de un cliente lógico a un `RestClient.Builder`: transporte Apache HttpClient 5 con pool y timeouts y sin reintentos propios, nombre de observación, traducción de errores y el interceptor axiz. |
| `AxizRestClientInterceptor` | Comportamiento por cliente de un `RestClient`: propagación de cabeceras, autenticación saliente, bulkhead y circuit breaker, reintentos solo para llamadas idempotentes dentro del timeout total y una línea de log por intento (solo la ruta, nunca el query string). |
| `ClientNameObservationConvention` | Usa el nombre lógico del cliente como `client.name` en métricas y spans. |
| `ClientResilience` | Decisiones de circuit breaker, bulkhead y reintento de un cliente. |
| `ExternalErrorHandler` | Traduce las respuestas de error de una dependencia a `ExternalServiceException` con la categoría elegida según el estado; un cuerpo vacío se conserva. |
| `HttpClientRegistry` | Configuración validada y componentes por cliente creados al arranque (resiliencia, credenciales, transporte), que se cierran con la aplicación. |
| `OutboundAuth` | Es dueño de la autenticación saliente de un cliente: client credentials de OAuth2 (en caché y renovadas con anticipación), reenvío del bearer token entrante o una API key del almacén de secretos. |
| `OutboundHeaders` | Cabeceras reenviadas a una dependencia desde la petición actual: id de correlación, idioma y las cabeceras del catálogo incluidas en la lista permitida del cliente. |
| `ReactiveClientNameObservationConvention` | Usa el nombre lógico del cliente como `client.name` en métricas y spans de un WebClient. |

## Credenciales reactivas

Los grupos reactivos usan `AsyncSecretResolver` y el puerto core `AsyncHttpExchange`. Las client
credentials de OAuth usan un transporte privado WebClient/Reactor Netty: DNS nativo, pool acotado (2
conexiones, 16 obtenciones pendientes, timeout de obtención de 5 segundos), plazo por petición, límite de
64 KiB para la respuesta del token y sin redirecciones. Las renovaciones concurrentes comparten una sola
petición y vuelven a leer el secreto actual al renovar. La cancelación llega a la búsqueda del secreto o
al intercambio HTTP; las credenciales no se delegan a workers.

El registro construye los recursos al arranque y cierra sus pools y event loops al cierre. Una
aplicación reactiva con configuración OAuth o API key y solo un resolvedor de secretos síncrono falla al
arranque. TLS usa el SSL bundle de cliente configurado (incluidos los key managers para mTLS) o la
confianza del sistema, capturados durante el arranque; la verificación del hostname permanece habilitada.

`OutboundAuth.headers()` expone mapas inmutables de valores `SecretValue` redactados, que se revelan solo
al construir las cabeceras de red. El filtro libera los permisos de bulkhead y circuit breaker ante
cancelación, errores y respuestas vacías. Los scopes de contexto locales conservan la observación del
llamador durante la obtención del token y los callbacks, sin instalar hooks globales de Reactor. La ruta
servlet conserva su API imperativa. La autenticación solo de CPU sigue siendo usable sin las dependencias
reactivas opcionales.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-http-client-spring-boot-autoconfigure verify`.

## Seguridad del destino y finalización de recursos

Los clientes con nombre exigen HTTPS al arranque. HTTP requiere `axiz.transport.allow-plain-http=true` y
`axiz.config.environment=local`, `test` o `ci`. Los clientes autenticados no pueden cambiar de origen
mediante una URL absoluta. Los transportes gestionados nunca siguen redirecciones automáticas; los SSL
bundles conservan la verificación del certificado y del hostname. Los destinos de tokens OAuth siguen la
misma política.

Cada permiso de resiliencia obtenido termina una sola vez, incluidos los fallos de transporte, de estado o
de notificación y la cancelación. Las respuestas que no se entregan se liberan antes del reintento o de la
propagación del error.
