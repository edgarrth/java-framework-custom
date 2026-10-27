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
    public-paths: []           # rutas sin token propias del servicio (ninguna por defecto)
    public-api-docs: false     # OpenAPI y Swagger UI sin token: solo el perfil local lo activa
    public-info: false         # /actuator/info sin token: expone versión y commit, cerrado
    actuator-authority: SCOPE_actuator
```

La lista de emisores normalmente vive en el ConfigMap `axiz-common`. Las llaves solo se obtienen
por `https` (salvo `localhost` en local).

Además de la firma (solo los algoritmos de `algorithms`), el emisor y la audiencia, cada token debe
traer **`exp`** (RFC 9068 la exige en un access token): sin ella se rechaza, en servlet y en
reactivo, porque un token sin vencimiento valdría para siempre. `exp` y `nbf` se comparan con el
reloj con la tolerancia de `clock-skew`. La Initializr aplica la misma regla a sus tokens.

El token se convierte en un `AxizPrincipal` (sujeto, client id, scopes, roles, tenant y atributos)
según `axiz.security.claims`, que mapea rutas de claims (`realm_access.roles`, `tid`...). Un token
cuyo emisor, client id, sujeto o tenant trae caracteres de control (U+0000 a U+001F, U+007F) se
rechaza con `401`: esos valores forman las llaves de caché, el alcance de idempotencia, la
auditoría y los logs, y un carácter de control podría hacer que dos llamantes se vean iguales. Las
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

En servlet se configura un `SecurityFilterChain` y en reactivo un `SecurityWebFilterChain`. Por
defecto la única ruta anónima es health (las sondas de Kubernetes); el resto del actuator exige
`actuator-authority`. El documento OpenAPI, Swagger UI y `/actuator/info` quedan cerrados salvo que
el servicio los abra con `public-api-docs` o `public-info` (los proyectos generados lo hacen solo
en el perfil `local`). Un token de un emisor que no está en la lista recibe 401 sin consultar
ninguna llave.

## Rate limit

Por cliente o por sujeto, siempre tomado del **token validado** (nunca de un header que el cliente
controla), en memoria o en Redis:

```yaml
axiz:
  security:
    rate-limit:
      enabled: true
      fail-open: true            # política global si Redis no responde
      rules:
        - name: catalog
          paths: [/v1/catalog/**]
          limit: 600
          window: 1m
          key: client
        - name: payments
          paths: [/v1/payments/**]
          limit: 100
          window: 1m
          key: client
          fail-open: false       # mover dinero: si el limitador no responde, se rechaza (429)
```

Cada regla puede tener su propia política ante una caída del almacén del limitador. Las rutas de
consulta suelen preferir disponibilidad (`fail-open: true`); las que mueven dinero o reciben
callbacks de terceros deben fallar cerrado (`fail-open: false`), porque perder el límite abre la
puerta a abuso o fraude justo cuando la plataforma está degradada.

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

- `RequestSignature`: firmas HMAC-SHA256 para callbacks y webhooks de partners (formato
  `AXIZ-HMAC-V2`, detalle abajo).
- `AesGcm`, `EnvelopeEncryptor` (data keys de KMS), `Rsa` (OAEP y PSS), `Jose` (JWS y JWE con lista
  fija de algoritmos), `Pbkdf2PasswordHasher`, `BlindIndex` para buscar por igualdad en columnas
  cifradas.
- Prohibidos: AES-ECB, CBC sin MAC, IV fijo, RSA PKCS#1 v1.5 (Semgrep los detecta).

`EnvelopeEncryptor` guarda en memoria las data keys para no llamar a KMS en cada mensaje, con
límites: una llave de cifrado se reusa como máximo `maxAge` y `maxUses` mensajes; una llave
descifrada, como máximo `maxAge` y solo para exactamente los mismos bytes de llave cifrada y el
mismo contexto (dos llaves cifradas distintas nunca comparten entrada, aunque su hash coincida,
así que un sobre siempre se abre con la llave que nombra su cabecera); y cada caché guarda a lo
sumo 1000 llaves, saliendo primero las menos usadas.

## Firmas de callbacks y webhooks

`RequestSignature` firma y verifica mensajes entre sistemas con HMAC-SHA256. La cadena firmada
tiene un campo por línea, y ninguno puede contener un salto de línea (cada uno se valida contra un
alfabeto estricto), así que dos requests distintas nunca producen la misma cadena:

```text
AXIZ-HMAC-V2
<key id>
<epoch en segundos>
<nonce>
<MÉTODO>
<ruta y query tal como viajan, en ASCII imprimible>
<SHA-256 del cuerpo en hexadecimal>
```

- **Rotación**: cada llave tiene un id; el firmante usa la activa y el verificador acepta todas
  las configuradas, así una rotación no corta el tráfico.
- **Ventana**: el timestamp debe estar dentro de la tolerancia (máximo 15 minutos) en ambos
  sentidos.
- **Un solo uso**: el nonce de un mensaje auténtico se registra en un `NonceStore` mientras la
  firma podría seguir siendo válida; un segundo envío recibe `REPLAYED`. En un clúster se usa
  `RedisNonceStore` (`SET NX PX`, atómico entre réplicas); `InMemoryNonceStore` protege una sola
  instancia. Si el almacén no responde, la verificación falla cerrada
  (`REPLAY_CHECK_UNAVAILABLE`).
- Solo los mensajes con firma válida consumen nonces: un atacante no puede "quemar" el nonce de un
  mensaje legítimo.
- La ruta se firma tal como viaja, sin normalizar: un proxy que la reescriba rompe la firma, que es
  el resultado seguro. Headers: `X-Axiz-Signature: keyId=<id>,v2=<hex>`, `X-Axiz-Timestamp`,
  `X-Axiz-Nonce`.

```java
RequestSignature signatures =
    new RequestSignature(keys, "2026-10", Duration.ofMinutes(5), clock, nonceStore);
Outcome outcome = signatures.verify(message, request.getHeader(RequestSignature.SIGNATURE_HEADER));
if (!outcome.valid()) {
  // 401 para MALFORMED, STALE, UNKNOWN_KEY, BAD_SIGNATURE y REPLAYED; 503 si el almacén no responde
}
```

## Contraseñas

`Pbkdf2PasswordHasher` acepta entre 100 000 y 10 000 000 iteraciones. Un hash almacenado se lee
con un único parser que valida formato, iteraciones, sal y largo: un registro corrupto o
manipulado nunca coincide, pide actualización y no consume CPU sin límite.

## Lo que no se hace

Obtener el JWKS a partir del `iss` del token sin lista blanca, `TestingAuthenticationToken` en
código productivo, todos los usuarios con el mismo rol, llaves de AWS de larga duración. Ver
[Anti-patrones](../calidad/anti-patrones.md).
