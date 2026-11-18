# axiz-tokenization-vault-spring-boot-autoconfigure

Composición del servidor genérico de tokenización. Requiere configuración explícita y los
colaboradores del modelo de ejecución elegido. No contiene SDK AWS/Redis ni depende del perfil
PAN. Su regla Maven de frontera neutral comprueba esas dependencias también transitivamente.

## Configuración

```yaml
spring:
  application:
    name: token-vault
axiz:
  tokenization:
    vault:
      auth:
        tenant-claim: tenant
        client-claim: client_id
        domains-claim: token_domains
        authority-prefix: "SCOPE_tokens:"
      types:
        DNI:
          profile: dni.pe
          version: 1
          keyset: identity
          domains: [billing]
          purposes: [customer-check]
          retention: 30d
```

El ejemplo ilustra nombres de política, no configura infraestructura productiva por sí solo.
Para un despliegue single tenant, sustituir `tenant-claim` por `fixed-tenant`; no pueden coexistir.
No se acepta un tenant implícito. Los perfiles incorporados son `dni.pe` v1 (ocho dígitos ASCII,
conserva ceros iniciales) y `text.identity` v1 (sin normalización). Otros perfiles se registran
como beans `TokenProfile`. Nombres/versiones repetidos o referencias desconocidas fallan al
arrancar. PAN se registra mediante su integración específica. Los metadatos permitidos se
declaran con `metadata-keys`; por defecto ninguno.

`TokenAccessPolicy` es un colaborador obligatorio para las restricciones propias del negocio.
El motor siempre aplica también autoridades, tenant/dominio y catálogo de propósitos. Una
excepción a la heurística PAN requiere la política versionada de `TokenTypePolicy`; no es un
parámetro del consumidor ni una autorización para guardar datos de autenticación prohibidos.

## Colaboradores y stacks

El stack imperativo necesita `TokenVault`, `EnvelopeEncryptor`, `BlindIndex`, `TokenBudget`,
`StrictAuditPublisher`, `TokenReconciliationLog`, `TokenReconciliationAlarm` y `TokenAccessPolicy`.
El stack WebFlux exige sus variantes nativas de almacén, cifrado, presupuesto, publicación y
conciliación; no satisface esos contratos usando beans bloqueantes. Un presupuesto con
`distributed() == false` falla al construir el motor. La conexión y políticas concretas de
Redis/DynamoDB se componen por integraciones separadas, no mediante un selector de proveedor
en este módulo base. Ninguna tabla se crea al arrancar esta composición.

Al terminar la inicialización se exige una sola implementación de cada colaborador obligatorio.
`@Primary` no permite ocultar duplicados; sustituir el motor tampoco omite la comprobación del
presupuesto ni la presencia del publicador estricto y de conciliación.

La presencia de una implementación de un SPI no demuestra por sí misma las garantías de un
proveedor personalizado: sus pruebas de aceptación deben acreditar persistencia, consistencia,
atomicidad, plazos, autorización y confirmación durable. El módulo no ofrece un almacén local
productivo ni una auditoría tolerante como reemplazo automático.

## Identidad y auditoría

`VerifiedTokenCaller.resolve(Authentication)` acepta solo un `JwtAuthenticationToken` autenticado,
con claims tipados y acotados. La autenticación debe provenir del resource server que verificó
firma, emisor, audiencia y vigencia. El resolver convierte esa identidad; **no valida firmas**
ni convierte un JWT construido manualmente en una credencial fiable. Recibe el argumento
explícitamente y no consulta headers, cuerpos ni `ThreadLocal`. En WebFlux se obtiene la
autenticación dentro de la cadena reactiva antes de llamar al resolver.

Las fachadas `StrictTokenizationAudit` y `AsyncStrictTokenizationAudit` comparten un mapeo cerrado
de evidencia: operación, sujeto/cliente verificados, tenant, tipo/dominio, propósito permitido,
resultado, identificador de operación y referencia de token enmascarada. Cada hecho obtiene un
ID independiente aunque comparta operación. No se incluyen valor, huella, ciphertext ni errores
del proveedor. Un resultado incierto conserva `UNKNOWN`; no se inventa un rollback. El sellado
y la confirmación durable corresponden al publicador estricto. Los fallos se propagan y el
motor no revela valores ni entrega el resultado antes del acuse requerido.

## Pruebas

Desde `axiz-framework`:

```sh
mvn -Pquality -pl spring/axiz-tokenization-vault-spring-boot-autoconfigure -am \
  -Dtest=VerifiedTokenCallerTest,StrictTokenizationAuditTest,VaultCompositionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Estos tests comprueban claims, configuración, selección de stack, dependencias obligatorias y
protocolo de publicación con dobles controlados. No acreditan por sí solos JWT remoto, red HTTP,
proveedores reales, políticas IAM, sellado persistente integrado ni cumplimiento PCI DSS.

## HTTP and in-process clients

The server registers five fixed POST routes: `/v1/tokens`, `/v1/tokens/lookup`,
`/v1/tokens/describe`, `/v1/tokens/reveal`, `/v1/tokens/delete`. Requests contain type/domain and
value or token; caller, tenant and authorities are obtained only from an authenticated JWT.
The resolver accepts the framework's `AxizAuthentication`. It does not verify signatures itself:
the resource-server security chain must validate issuer, audience, signature and token lifetime.
The engine then checks operation scopes, registered profile/domain and purpose.

Transport must be HTTPS. `axiz.tokenization.vault.http.allow-insecure=true` is permitted only with
`axiz.config.environment=local|test|ci`; a forwarded header is not accepted directly as proof of TLS.
At a TLS-terminating ingress, configure Spring's trusted proxy handling and restrict network access
to that ingress. Requests are UTF-8 JSON, bounded to 64 KiB, with duplicate keys and unknown fields
rejected. Value contents are limited separately to 8192 UTF-8 bytes. Query parameters,
`Content-Encoding` and generic `Idempotency-Key` requests are rejected.

Successful tokenization returns 201 for creation or 200 for an existing association. Lookup and
describe return a permitted result or `AXZ-TOKEN-0404`. Reveal writes the authorized canonical value
through a private codec after strict audit confirmation. Delete returns 204 with no body and
`X-Axiz-Token-Deleted: true|false`, preserving the Java boolean result without putting a token in a URL.
All protected routes enforce `Cache-Control: no-store`, including security errors. Rate limiting
returns a bounded `Retry-After`; `AXZ-TOKEN-0504` remains nonretryable uncertainty.

The shared `HttpPayloadPolicy` veto prevents built-in logging and idempotency filters from capturing
these route bodies, including encoded/matrix path variants. Detailed Spring request logging is
rejected at startup. Application-provided interceptors, proxies and APM agents must likewise avoid
payload capture. This policy cannot control instrumentation installed outside the framework.
Owned buffers and late values are closed on termination/cancellation; immutable strings and copies
retained by the HTTP/TLS implementation have only best-effort memory erasure.

`CallerBoundTokenizer` resolves imperative security context at each invocation and delegates to
`VaultOperations`. `ReactiveCallerBoundTokenizer` resolves identity within each subscription and
calls `AsyncVaultOperations`; it does not wrap synchronous storage or infer identity in a future
from thread-local state. For a separate service, use the consumer starter instead.
