<!-- generated -->
# axiz-core-http

Modelo de cliente HTTP: configuración por cliente, autenticación saliente, reglas de reintento, traducción de errores y propagación de cabeceras.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-http</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AccessToken` | Token de acceso OAuth2. |
| `AccessTokenProvider` | Proporciona tokens de acceso para las llamadas salientes. |
| `AsyncAccessTokenProvider` | Obtención nativa de tokens con cancelación controlada por el llamador. |
| `AsyncHttpExchange` | Contrato de petición y respuesta del JDK para HTTP acotado y cancelable, con DNS nativo y sin redirecciones. |
| `ClientCredentialsAsyncTokenProvider` | Agrupa la renovación nativa y la búsqueda asíncrona del secreto a través del puerto HTTP. |
| `AuthSettings` | Configuración de autenticación saliente. |
| `AuthType` | Autenticación saliente de un cliente HTTP. |
| `ClientSettings` | Configuración de un cliente HTTP lógico (`axiz.http-client.clients.<name>`). |
| `ExternalErrorTranslator` | Traduce una respuesta de error de una dependencia a una `ExternalServiceException`. |
| `HeaderPropagation` | Calcula las cabeceras que se reenvían a una dependencia: siempre el identificador de correlación y Accept-Language, más la lista permitida del cliente. |
| `HttpRetryRules` | Decisión de reintento de una llamada HTTP saliente según la configuración del cliente. |

Los proveedores síncrono y nativo comparten la codificación del formulario OAuth y la validación de
la respuesta. La expiración se cuenta desde el envío; se rechazan las respuestas que llegan vencidas,
los valores de expiración inválidos y los tipos de token no soportados. El diagnóstico de un JSON
inválido nunca incluye el token. Los tokens de acceso y las respuestas de token ocultan su
representación en texto y se marcan CRITICAL para enmascararse en todos los canales. Las cargas
nativas pendientes usan la caché acotada común, de modo que la cancelación de un llamador deja
activos a los demás y la cancelación del último llega al transporte o a la búsqueda del secreto.

El proveedor nativo toma prestado `AsyncHttpExchange`; core no depende de Reactor, Netty ni Spring.
El transporte debe imponer el timeout de la petición, el límite de bytes y la cancelación sin seguir
redirecciones. La capacidad de Spring aporta una implementación propia con WebClient y Netty. Las
pruebas unitarias usan un driver HTTP local del JDK, independiente de ese transporte de producción.

Tipos relacionados: `ClientCredentialsTokenProvider` están en `axiz-adapter-http-jdk`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-http verify`.
