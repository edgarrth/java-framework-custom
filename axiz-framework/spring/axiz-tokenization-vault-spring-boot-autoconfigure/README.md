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

## Clientes HTTP y en proceso

El servidor registra cinco rutas POST fijas: `/v1/tokens`, `/v1/tokens/lookup`,
`/v1/tokens/describe`, `/v1/tokens/reveal`, `/v1/tokens/delete`. Las peticiones contienen tipo y dominio
y un valor o token; el llamador, el tenant y las autoridades se obtienen solo de un JWT autenticado.
El resolvedor acepta el `AxizAuthentication` del framework. No verifica firmas por sí mismo: la cadena de
seguridad del resource server debe validar emisor, audiencia, firma y vigencia del token. Luego el motor
comprueba los scopes de la operación, el perfil y dominio registrados y el propósito.

El transporte debe ser HTTPS. `axiz.tokenization.vault.http.allow-insecure=true` se permite solo con
`axiz.config.environment=local|test|ci`; una cabecera reenviada no se acepta directamente como prueba de
TLS. En un ingress que termina TLS se configura el manejo de proxies confiables de Spring y se restringe
el acceso de red a ese ingress. Las peticiones son JSON UTF-8, limitadas a 64 KiB, y se rechazan las
claves duplicadas y los campos desconocidos. El contenido de los valores se limita por separado a 8192
bytes UTF-8. Se rechazan los parámetros de consulta, `Content-Encoding` y las peticiones con
`Idempotency-Key` genérico.

Una tokenización exitosa devuelve 201 al crear o 200 para una asociación existente. Lookup y describe
devuelven un resultado permitido o `AXZ-TOKEN-0404`. Reveal escribe el valor canónico autorizado mediante
un codec privado después de la confirmación estricta de la auditoría. Delete devuelve 204 sin cuerpo y
`X-Axiz-Token-Deleted: true|false`, conservando el resultado booleano de Java sin poner un token en una URL.
Todas las rutas protegidas imponen `Cache-Control: no-store`, incluidos los errores de seguridad. El límite
de tasa devuelve un `Retry-After` acotado; `AXZ-TOKEN-0504` sigue siendo una incertidumbre no reintentable.

El veto compartido `HttpPayloadPolicy` impide que los filtros incorporados de logging e idempotencia
capturen los cuerpos de estas rutas, incluidas las variantes de ruta codificadas o con matrix. El logging
detallado de peticiones de Spring se rechaza al arranque. Los interceptores, proxies y agentes APM
provistos por la aplicación también deben evitar capturar payloads. Esta política no puede controlar la
instrumentación instalada fuera del framework. Los buffers propios y los valores tardíos se cierran al
terminar o cancelar; los strings inmutables y las copias que retenga la implementación HTTP/TLS solo tienen
un borrado de memoria de mejor esfuerzo.

`CallerBoundTokenizer` resuelve el contexto de seguridad imperativo en cada invocación y delega en
`VaultOperations`. `ReactiveCallerBoundTokenizer` resuelve la identidad dentro de cada suscripción y llama
a `AsyncVaultOperations`; no envuelve almacenamiento síncrono ni deduce la identidad en un future a partir
del estado ligado al hilo. Para un servicio separado se usa el starter del consumidor.
