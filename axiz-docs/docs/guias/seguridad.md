# Seguridad

## Resource server

Cada servicio valida tokens JWT de una **lista blanca de emisores**; un token de cualquier otro
emisor se rechaza antes de buscar llaves, y las llaves vienen del JWKS configurado o descubierto
desde los metadatos OIDC del emisor, nunca del token.

```yaml
axiz:
  security:
    issuers:
      - issuer: https://sso.axiz.internal/realms/axiz
        audiences: [payments-business]
        # jwk-set-uri opcional: si falta, se descubre de /.well-known/openid-configuration
    algorithms: [RS256, PS256, ES256]
    clock-skew: 30s
    public-paths: [/v3/api-docs/**, /swagger-ui/**]
    actuator-authority: SCOPE_actuator
```

La lista de emisores normalmente vive en el ConfigMap `axiz-common`. Las llaves solo se obtienen
por `https` (salvo `localhost` en local).

El token se convierte en un `AxizPrincipal` (sujeto, client id, scopes, roles, tenant y atributos)
según `axiz.security.claims`, que mapea rutas de claims (`realm_access.roles`, `tid`...). Las
authorities quedan como `SCOPE_<scope>` y `ROLE_<ROL>`:

```java
@PreAuthorize("hasAuthority('SCOPE_payments:write')")
```

**Personas y aplicaciones.** Un token de client credentials es de una aplicación, no de una
persona, y las políticas de acceso dependen de esa diferencia (`Caller.isMachine()`). El framework
lo reconoce de tres formas:

- cuando `sub` es igual al client id (RFC 9068, Cognito, Okta);
- cuando no hay `sub`;
- cuando el token tiene una de las marcas de `axiz.security.claims.machine`.

Las marcas por defecto son `idtyp=app` (Microsoft Entra) y
`preferred_username^=service-account-` (cuentas de servicio de Keycloak, cuyo `sub` es el id de
un usuario técnico). Cada marca puede tener una de estas formas:

- `claim`: el claim está presente;
- `claim=valor`: el claim tiene ese valor;
- `claim^=prefijo`: el claim empieza con ese prefijo.

Con otro proveedor, configura la marca que distinga sus tokens de aplicación. Si no la
configuras, una cuenta de servicio se tratará como una persona y las políticas le negarán el
acceso.

En servlet se configura un `SecurityFilterChain` y en reactivo un `SecurityWebFilterChain`;
health e info son las únicas rutas anónimas del actuator.

## Rate limit

Por cliente o por sujeto, siempre tomado del **token validado** (nunca de un header que el cliente
controla), en memoria o en Redis:

```yaml
axiz:
  security:
    rate-limit:
      enabled: true
      fail-open: true
      rules:
        - name: payments
          paths: [/v1/payments/**]
          limit: 100
          window: 1m
          key: client
```

## Autorización por recurso

Un scope o un rol dice *qué tipo de operación* puede hacer el llamador; no dice *sobre qué
recurso*. En una entidad financiera la segunda pregunta es la que importa: un cliente autenticado
no debe leer ni operar órdenes, cuentas o pagos de otro cliente cambiando un id en la URL (BOLA,
OWASP API1).

Con CQRS, cada comando o consulta que nombra un recurso tiene una `AccessPolicy`:

```java
@Component
public class GetPaymentOrderAccess implements AccessPolicy<GetPaymentOrderQuery> {

  @Override
  public Class<GetPaymentOrderQuery> requestType() {
    return GetPaymentOrderQuery.class;
  }

  @Override
  public void check(GetPaymentOrderQuery request, Caller caller) {
    String owner = orders.find(request.orderId()).map(PaymentOrder::customerId).orElse(null);
    if (owner == null || !PaymentAuthorities.canRead(caller, owner)) {
      throw new NotFoundException("payment-order", request.orderId()); // no revela que existe
    }
  }
}
```

- `Caller` resume el token: `subject`, `clientId`, `tenant`, `issuer` y authorities.
  `isUser(id)` indica un usuario final con ese sujeto y `isMachine()` un token de client
  credentials.
- Las políticas corren en la etapa de autorización del pipeline. Esto ocurre después de
  `@RequiresAuthority` y antes de la idempotencia, la transacción y el handler. En buses reactivos,
  una `AccessPolicy` corre fuera del event loop. Si la política lee estado a través de un
  repositorio reactivo, usa `ReactiveAccessPolicy`, que devuelve `Mono<Void>` y no bloquea. Las
  reglas de arquitectura de los servicios reactivos prohíben `block()`.
- Hay dos formas de negar: `AccessException` (403) y `NotFoundException` (404). Usa 404 cuando
  confirmar que el recurso existe ya sería una fuga.
- Los comandos internos que no nombran recursos de un tercero, por ejemplo los que proyectan
  eventos, se marcan con `@OpenAccess(reason = "...")`. El motivo queda en el código y se revisa.
- `axiz.cqrs.authorization.unprotected` decide qué hacer con los comandos y consultas sin política
  ni `@OpenAccess`: `warn` (por defecto) solo registra un aviso, `fail` impide arrancar y `allow` no
  hace nada. Los servicios de dominio usan `fail`.

En HTTP, `@PreAuthorize` y las políticas se evalúan antes de que la idempotencia devuelva una
respuesta guardada. Así, un replay nunca entrega datos a quien ya no tiene permiso (ver
[Idempotencia](idempotencia.md#autorizacion-antes-del-replay)).

La demo aplica esta matriz en payments-business:

| Operación | Cliente (token de canal) | payment-process (`payments:process`) | Lectura (`payments:read`) |
|---|---|---|---|
| Crear orden | solo para sí mismo | no | no |
| Cambiar estado | no | sí | no |
| Ver una orden | solo las suyas (otras → 404) | sí | sí |
| Listar órdenes | solo las suyas | sí | sí |

`PaymentAccessMatrixTest` cubre cada celda de la tabla. Además, core-integration envía el titular
al core bancario, que rechaza el débito de una cuenta ajena (`PAYMENTS-COREINTEGRATION-0004`). Con
eso, la propiedad de la cuenta se controla en el sistema de registro y no solo en la orden.

## Auditoría durable

El publicador de auditoría por defecto es asíncrono: un evento puede perderse si el proceso cae
antes de enviarlo. Para cambios de negocio eso no basta. Con
`axiz.audit.durable.enabled: true`, el evento de auditoría se escribe en el outbox dentro de la
misma transacción del cambio: se confirman los dos o no se confirma ninguno.

```yaml
axiz:
  audit:
    durable:
      enabled: true
      destination: audit            # destino lógico en axiz.messaging.destinations
      outside-transaction: async    # async | fail
```

- Requiere `axiz-core-outbox` y un `OutboxWriter`.
- Los eventos de fallo (acceso denegado, validación) no tienen transacción de negocio que
  confirmar, porque esta se revierte. Por eso van por el publicador asíncrono.
- Si un evento se emite fuera de una transacción, `async` lo envía por el publicador asíncrono y
  `fail` lanza `AuditPublishException`. `fail` sirve para detectar en pruebas qué código audita sin
  transacción.
- Métricas: `axiz.audit.pending`, `axiz.audit.lost`, `axiz.audit.fallback`, `axiz.audit.failures`
  y `axiz.audit.worker.alive`. Alerta si `lost` es mayor que 0 o si `worker.alive` vale 0 (ver
  [Observabilidad](../operacion/observabilidad.md#alertas-sugeridas)).

## Llamadas salientes

- **Client credentials** con token en caché y renovación anticipada.
- **Token relay** solo dentro de la misma cadena de audiencias (BFF → dominio).
- **API key** y **mTLS** con material desde Secrets Manager o SSL bundles.

Ver [Clientes HTTP y SOAP](clientes.md).

## Firmas y criptografía

- `RequestSignature`: HMAC-SHA256 para callbacks y webhooks de partners.
- `AesGcm`, `EnvelopeEncryptor` (data keys de KMS), `Rsa` (OAEP y PSS), `Jose` (JWS y JWE con lista
  fija de algoritmos), `Pbkdf2PasswordHasher`, `BlindIndex` para buscar por igualdad en columnas
  cifradas.
- Prohibidos: AES-ECB, CBC sin MAC, IV fijo, RSA PKCS#1 v1.5 (Semgrep los detecta).

## Lo que no se hace

Obtener el JWKS a partir del `iss` del token sin lista blanca, `TestingAuthenticationToken` en
código productivo, todos los usuarios con el mismo rol, llaves de AWS de larga duración. Ver
[Anti-patrones](../calidad/anti-patrones.md).
