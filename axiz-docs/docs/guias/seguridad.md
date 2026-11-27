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
        profile: compatible   # strict exige access tokens con typ=at+jwt
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

El perfil se fija por emisor mediante `axiz.security.issuers[].profile`. `compatible`, el valor
predeterminado, acepta `typ=JWT` o un encabezado sin `typ`. `strict` acepta exclusivamente
`typ=at+jwt` o `typ=application/at+jwt`, exige `iat` y los claims textuales no vacíos `sub`,
`client_id` y `jti`, y excluye RS256 de los algoritmos habilitados. La aplicación no arranca si
un emisor estricto queda sin algoritmos. PS256 y ES256 están disponibles de forma predeterminada.
No hay reintento con otro perfil cuando un token falla: cada emisor tiene un único contrato y
los tipos admitidos por ambos perfiles son excluyentes. `id_token` se rechaza en ambos.

Los tipos y claims del perfil estricto siguen el
[perfil de access tokens de RFC 9068](https://www.rfc-editor.org/rfc/rfc9068.html#section-4).
Excluir RS256 es una política de endurecimiento de Axiz, no una exigencia de esa RFC ni una
declaración de conformidad completa. Antes de activar `strict`, configura el proveedor de
identidad para emitir el tipo, los claims y un algoritmo admitido. Las URLs y claves que un
token incluya en `jku` o `jwk` nunca sustituyen al JWKS del emisor configurado.

El token se convierte en un `AxizPrincipal` (sujeto, client id, scopes, roles, tenant y atributos)
según `axiz.security.claims`, que mapea rutas de claims (`realm_access.roles`, `tid`...). Un token
cuyo emisor, client id, sujeto o tenant trae caracteres de control (U+0000 a U+001F, U+007F) se
rechaza con `401`: esos valores forman las llaves de caché, el alcance de idempotencia, la
auditoría y los logs, y un carácter de control podría hacer que dos llamantes se vean iguales. Las
authorities quedan como `SCOPE_<scope>` y `ROLE_<ROL>`:

```java
@PreAuthorize("hasAuthority('SCOPE_payments:write')")
```

Spring lee y valida el cuerpo de la petición antes de que el interceptor de `@PreAuthorize` del
controlador se ejecute; sin más, un llamante sin el scope recibiría un `400` con la lista de campos
del contrato. Axiz evalúa antes de leer el cuerpo toda expresión `@PreAuthorize` del controlador
(de método o de clase) que no lea argumentos: `HandlerInterceptor` en servlet y `WebFilter`
posterior a la cadena de seguridad en WebFlux. El llamante sin permiso recibe `403` sin conocer el
contrato (OWASP API5). Las expresiones que leen argumentos (`#id`, `#p0`) solo pueden decidirse
después del binding y conservan ese orden; para endpoints sensibles, autoriza primero por scope y
deja la comprobación de argumentos en el caso de uso.

**Personas y aplicaciones.** Un token de client credentials es de una aplicación, no de una
persona, y las políticas de acceso dependen de esa diferencia (`Caller.isMachine()`). El framework
lo reconoce de tres formas:

- cuando `sub` es igual al client id (RFC 9068, Cognito, Okta);
- cuando no hay `sub`;
- cuando el token tiene una de las marcas de `axiz.security.claims.machine`.

La marca por defecto es `idtyp=app` (Microsoft Entra). Otros proveedores deben emitir un
**claim de tipo de token controlado por el emisor**, cuyo valor no provenga del perfil del usuario.
Solo se admite la comparación exacta `claim=valor`, sensible a mayúsculas. Las marcas por presencia,
prefijos y atributos de perfil como `preferred_username`, `name` o `email` se rechazan al iniciar.
Un nombre como `service-account-payments-web` nunca convierte a una persona en aplicación.

```yaml
axiz:
  security:
    claims:
      machine: [token_kind=service]
      subject-as-client: false
```

El proveedor debe garantizar que `token_kind=service` solo aparezca en tokens de aplicación,
incluso cuando permita atributos personalizados o federación. Esa garantía debe cumplirse para
todos los emisores admitidos por el servicio. Para proveedores con modelos distintos, se puede
registrar un `ClaimsMapper` propio que aplique una política explícita por emisor.

**Marca de cuentas técnicas.** No hay marca automática por username. En el demo, únicamente los clientes confidenciales con client credentials habilitado y
los flujos de usuario deshabilitados emiten `axiz_token_kind=service` mediante un mapper fijo del
cliente; los servicios configuran esa marca exacta. No se debe instalar ese mapper en un scope
compartido con usuarios ni en un cliente que admita también flujos de usuario. Una cuenta técnica
con UUID en `sub`, sin la marca configurada, se trata como usuario: configurar la marca en el
emisor y comprobar ambos tipos de token.

`subject-as-client` vale `false` por defecto. Activarlo solo permite usar `sub` como client id
cuando **también coincide una marca de aplicación confiable**. Un token de usuario sin `azp` o
`client_id` se rechaza; habilitar la opción no convierte automáticamente a cualquier sujeto en
aplicación. Siguen admitidos los formatos estándar con `sub` igual al client id o sin usuario final.

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
      store: redis              # obligatorio; memory solo en local/test/ci
      rules:
        - name: catalog
          paths: [/v1/catalog/**]
          limit: 600
          window: 1m
          key: client
          fail-open: true        # política explícita de esta regla
        - name: payments
          paths: [/v1/payments/**]
          limit: 100
          window: 1m
          key: client
          fail-open: false       # mover dinero: si el almacén no responde, se rechaza (503)
```

Cada regla debe declarar su propia política ante una caída del almacén del limitador. Las rutas de
consulta suelen preferir disponibilidad (`fail-open: true`); las que mueven dinero o reciben
callbacks de terceros deben fallar cerrado (`fail-open: false`), porque perder el límite abre la
puerta a abuso o fraude justo cuando la plataforma está degradada.

Servlet y WebFlux aplican el limitador después de la autorización HTTP. La identidad del emisor
se codifica con SHA-256 para que emisores con el mismo `String.hashCode()` no compartan cuota.

Para rutas públicas del gateway puede declararse otra regla con `key: anonymous-ip`; es optativa
y solo corresponde a llamadas sin identidad Axiz. Requiere la misma selección de almacén y
política `fail-open` explícita. Se usa la dirección de conexión y no se leen `X-Forwarded-For` ni
`Forwarded` en el limitador. Si un proxy o el servidor reescribe esa dirección, la aplicación
debe aceptar esa reescritura únicamente desde proxies de confianza; en otro caso la cuota será
por proxy. Una dirección no disponible impide aplicar la cuota y produce `503`.

```yaml
axiz:
  security:
    rate-limit:
      enabled: true
      store: redis
      rules:
        - name: public-gateway
          paths: [/public/**]
          key: anonymous-ip
          limit: 30
          window: 1m
          fail-open: false
```

La regla no hace pública una ruta: su autorización se configura por separado. Las reglas de
llamantes autenticados y anónimos pueden cubrir la misma ruta sin ocultarse entre sí.

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
  repositorio reactivo, usa `pe.axiz.extension.cqrs.ReactiveAccessPolicy` del módulo API, que devuelve
  `Publisher<Void>` (por ejemplo, `Mono<Void>`) y no bloquea. Las
  reglas de arquitectura de los servicios reactivos prohíben `block()`.
- Hay dos formas de negar: `AccessException` (403) y `NotFoundException` (404). Se usa 404 cuando
  confirmar que el recurso existe ya sería una fuga.
- Los comandos internos que no nombran recursos de un tercero, por ejemplo los que proyectan
  eventos, se marcan con `@OpenAccess(reason = "...")`. El motivo queda en el código y se revisa.
- `axiz.cqrs.authorization.unprotected` decide qué hacer con los comandos y consultas sin política
  ni `@OpenAccess`: `warn` solo registra un aviso, `fail` (por defecto) impide arrancar y `allow` no
  hace nada. Un valor desconocido impide arrancar, incluso si aún no hay handlers. Los servicios de dominio usan `fail`.

En HTTP, `@PreAuthorize` y las políticas se evalúan antes de que la idempotencia devuelva una
respuesta guardada. Así, un replay nunca entrega datos a quien perdió el permiso (ver
[Idempotencia](idempotencia.md#autorizacion-antes-del-replay)).

La demo aplica esta matriz en payments-business:

| Operación | Cliente (token de canal) | payment-process (`payments:process`) | Lectura (`payments:read`) |
|---|---|---|---|
| Crear orden | solo para sí mismo | no | no |
| Cambiar estado | no | sí | no |
| Ver una orden | solo las suyas (otras -> 404) | sí | sí |
| Listar órdenes | solo las suyas | sí | sí |

`PaymentAccessMatrixTest` cubre cada celda de la tabla. Además, core-integration envía el titular
al core bancario, que rechaza el débito de una cuenta ajena (`PAYMENTS-COREINTEGRATION-0004`). Con
eso, la propiedad de la cuenta se controla en el sistema de registro y no solo en la orden.

## Auditoría durable

Fuera de `local`/`test`, la auditoría administrada por Axiz exige una elección explícita:
`axiz.audit.mode: ASYNC`, `DURABLE` o `SYNC`. Solo en desarrollo se permite ASYNC implícito:
puede perder eventos si el proceso cae antes de enviarlos. Los selectores booleanos heredados
`durable.enabled` y `async.enabled` se rechazan. Los publicadores propios definen su política.

Con `DURABLE`, el evento se escribe en el outbox dentro de la misma transacción del cambio:
se confirman los dos o ninguno. El negocio y el outbox deben compartir recurso transaccional;
un outbox presente en el classpath no demuestra esa integración. El generador selecciona DURABLE
si incluye la capacidad outbox y ASYNC en caso contrario, con comentarios visibles sobre la garantía.

```yaml
axiz:
  audit:
    mode: DURABLE
    durable:
      destination: audit            # destino lógico en axiz.messaging.destinations
      outside-transaction: async    # async | fail
```

- Requiere `axiz-core-outbox`, un `OutboxWriter` y destino/relay configurados.
- `axiz.audit.async.delivery-timeout` limita la entrega imperativa y nativa (10 s por defecto).
  El cierre de la cola conserva un presupuesto separado de 10 s. La cancelación imperativa es
  cooperativa: el transporte también necesita timeouts y un delegado que ignore la interrupción
  puede completar tarde. ASYNC y su fallback no equivalen a persistencia durable.
- En modo DURABLE, los fallos se registran después del rollback mediante una transacción corta
  independiente. La persistencia tiene timeout y concurrencia acotados. Si no se confirma,
  el evento lleva `NOT_CONFIRMED` y se conserva el error de negocio; el fallback no se presenta
  como evidencia durable. `transactionOutcome` y `operationOutcome` son ejes distintos: un rollback
  local no demuestra ausencia de efectos en sistemas externos.
- Si un evento se emite fuera de una transacción, `async` lo envía por el publicador asíncrono y
  `fail` lanza `AuditPublishException`. `fail` sirve para detectar en pruebas qué código audita sin
  transacción.
- Métricas: `axiz.audit.pending`, `axiz.audit.lost`, `axiz.audit.fallback`, `axiz.audit.failures`
  y `axiz.audit.worker.alive`. Alerta si `lost` es mayor que 0 o si `worker.alive` vale 0 (ver
  [Observabilidad](../operacion/observabilidad.md#alertas-sugeridas)).

Los eventos se sellan por defecto con `axiz.audit.integrity.mode=HASH`. Para autenticidad usa
`HMAC`, una referencia de secreto independiente en `integrity.hmac-secret` y un identificador
de rotación `integrity.hmac-key-id`. Un hash sin clave no impide que quien pueda reescribir la
evidencia recalcule la cadena. Los detalles quedan congelados y saneados antes del sellado.

La vía `axiz.audit.strict.enabled=true` proporciona puertos explícitos de publicación confirmada:
`strict.mode=DIRECT` exige un destino durable atestado y `DURABLE` un commit independiente de
outbox. No sustituye silenciosamente el destino por cola o log. Sus errores se propagan y no
deshacen efectos de negocio ya confirmados. La bóveda usa esta vía para su evidencia obligatoria.

Con una vía estricta disponible, las cadenas en memoria emiten checkpoints confirmados cada
`strict.checkpoint-interval` (1 minuto por defecto). `AuditChainVerifier` informa integridad y
completitud por separado. Sin un checkpoint confiable, la completitud es `NOT_ASSESSED`; con él
solo se prueba hasta su secuencia. Conserva checkpoints y claves de verificación fuera del
control del escritor. Una métrica local o un enqueue exitoso no acredita recepción completa.

## Llamadas salientes

- **Client credentials** con token en caché y renovación anticipada.
- **Token relay** solo dentro de la misma cadena de audiencias (BFF -> dominio).
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

## Descubrimiento y disponibilidad de claves JWT

`axiz.security.jwks.connect-timeout` vale `2s` y `read-timeout` vale `3s` por defecto.
Ambos deben estar entre `1ms` y `10s`. El transporte nativo JDK limita la conexión y el tiempo
completo de cada descarga a la suma de ambos plazos, incluidos headers y cuerpo parcial.
Los documentos se limitan a 128 KiB, UTF-8/JSON válidos, sin redirecciones; JWKS admite hasta
100 claves. Servlet espera ese futuro acotado; WebFlux se suscribe al transporte asíncrono,
sin envolver I/O bloqueante en un executor. Los recursos se cierran con el contexto.

El descubrimiento consulta únicamente emisores configurados y exige coincidencia exacta del
campo `issuer`. El `jwks_uri` descubierto también se valida. Un `iss` desconocido no produce
tráfico saliente. Los endpoints requieren HTTPS; `local`, `test` y `ci` permiten HTTP para
fixtures. Fuera de esos entornos se necesita `axiz.security.allow-http-jwks-in-cluster=true`
para la excepción explícita de HTTP; el valor por defecto es `false`.

Cada endpoint admite una llamada saliente en curso. Tras fallo o documento inválido, la espera
crece de 1 a 60 segundos; las peticiones durante esa espera fallan cerradas. El arranque resuelve
metadatos en segundo plano y reintenta con esa misma espera, sin impedir iniciar el proceso.
Eso no indica que autenticación esté disponible: un token que necesita un emisor caído recibe
503, `Retry-After` y `Cache-Control: no-store`. No recibe una identidad sin verificar.
Las claves que Nimbus ya tiene en caché siguen verificando tokens localmente.

Una descarga correcta de JWKS se reutiliza al menos durante un segundo, incluso ante distintos
`kid` desconocidos, para acotar las actualizaciones forzadas por tokens forjados. Un cambio de
clave puede tardar ese intervalo en aparecer. Un token inválido con claves disponibles sigue
produciendo 401; indisponibilidad del emisor y credenciales inválidas son casos distintos.

`JwksAvailabilityTest` prueba red real de loopback, descubrimiento/keys, cuerpo parcial,
redirecciones, tamaño, emisor incoherente, recuperación y cadenas servlet/reactiva. El test
`NativeJwksTest` exige además BlockHound en descubrimiento y primera verificación.
