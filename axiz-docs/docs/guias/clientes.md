# Clientes HTTP y SOAP

## HTTP: interfaces `@HttpExchange`

axiz estandariza un solo tipo de cliente: interfaces de Spring (`@HttpExchange`) registradas como
**grupos de servicios HTTP**, uno por dependencia. Retrofit, Feign y otros clientes paralelos están
prohibidos.

```bash
axiz generate http-client core-banking --contract contracts/core-banking.yaml
```

Genera la interfaz (con sus modelos, sin servidor) y su registro:

```java
@HttpExchange
public interface CoreBankingClient {
  @PostExchange("/core/v2/accounts/{account}/debits")
  CoreMovement postDebit(
      @PathVariable("account") String account,
      @RequestHeader(name = "X-Request-Ref", required = true) String xRequestRef,
      @RequestBody CoreDebit body);
}

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "core-banking", types = CoreBankingClient.class,
    clientType = HttpServiceGroup.ClientType.REST_CLIENT)   // WEB_CLIENT en reactivo
public class CoreBankingClientConfiguration {}
```

Todo lo demás es configuración del cliente con nombre:

```yaml
axiz:
  http-client:
    clients:
      core-banking:
        base-url: https://core-banking.qa.internal     # ConfigMap del ambiente
        connect-timeout: 2s
        read-timeout: 4s
        total-timeout: 8s          # presupuesto que incluye reintentos
        retry: IDEMPOTENT_ONLY     # o NONE
        max-attempts: 2
        circuit-breaker: default   # vacío lo desactiva
        max-concurrent-calls: 40   # bulkhead; 0 lo desactiva
        propagate-headers: [x-channel]
        auth:
          type: CLIENT_CREDENTIALS  # NONE, TOKEN_RELAY, API_KEY, MTLS
          token-uri: https://sso.axiz.internal/realms/core/protocol/openid-connect/token
          client-id: core-integration
          client-secret-ref: core-banking-client-secret
          scopes: [movements]
```

- **Timeouts obligatorios**: no existe un valor "infinito" por defecto.
- **Reintentos solo seguros**: métodos idempotentes o requests con `Idempotency-Key`, con backoff
  exponencial con jitter, dentro del presupuesto total.
- **Headers propagados**: correlación y `Accept-Language` siempre; los demás solo si están en la
  lista del cliente.
- **Observación**: un span CLIENT y métricas por cliente con su nombre lógico.
- **Errores**: las respuestas de error se convierten en `ExternalServiceException` con el detalle del
  proveedor, para que el adaptador las traduzca al dominio.
- **Secretos por referencia** (`*-ref`), resueltos con `SecretResolver` desde Secrets Manager.
- **Token relay** (`TOKEN_RELAY`): reenvía el token de la request entrante. En servlet se toma del
  contexto de seguridad del hilo; en reactivo, del contexto de Reactor de la request, así que
  funciona igual aunque la llamada cambie de hilo.

## SOAP

Para sistemas legacy, `SoapClient` sobre el `HttpClient` del JDK con timeout obligatorio,
WS-Security UsernameToken, parseo endurecido contra XXE, faults convertidos en errores externos y
log enmascarado. UsernameToken es autenticación legacy, por eso:

- `password-mode` no tiene valor por defecto: `TEXT` (la clave viaja dentro de TLS) o
  `LEGACY_SHA1_DIGEST` (el digest del perfil UsernameToken 1.0, que fija SHA-1; solo cuando la
  contraparte lo exige, y su uso queda registrado al crear el cliente).
- Con credenciales el endpoint debe ser `https://`; `allow-plain-http: true` existe solo para los
  sustitutos locales (docker compose, kind).
- Si la contraparte lo soporta, se prefiere un perfil moderno (TLS mutuo, X.509 o tokens).

```yaml
axiz:
  soap:
    clients:
      core-credit:
        endpoint: https://core-credit.qa.internal/soap/credit
        timeout: 8s
        username: core-integration
        password-ref: core-soap-password
        password-mode: LEGACY_SHA1_DIGEST   # lo exige el core de créditos
```

En el demo, `core-integration` usa los tres modos: REST con client credentials (core bancario),
REST con API key (red de pagos) y SOAP con WS-Security (core de créditos).
