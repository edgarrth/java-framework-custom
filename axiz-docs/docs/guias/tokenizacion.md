# Tokenización y bóveda central

La tokenización guarda una asociación entre un token aleatorio, un valor cifrado y un índice
HMAC de igualdad. La bóveda pertenece a esta capacidad; las tablas de negocio conservan el
token. El resultado público contiene `token`, `type`, `created` y metadatos expresamente
permitidos. El cifrado y el HMAC permanecen dentro de la bóveda.

Para buscar por el valor original se usa `lookup`: calcula el índice dentro del servidor y
retorna el token existente, sin crear otro. No hace falta distribuir el índice a consumidores.
El token no es una credencial: cada operación requiere identidad verificada, autorización,
cuotas y auditoría. El mismo valor en otro tenant, tipo o dominio tiene una asociación distinta.

## Elegir la capacidad

| Necesidad | Capacidad | Persistencia |
| --- | --- | --- |
| Tokenizar/revelar con custodia central | `tokenization` como cliente remoto | Bóveda Axiz |
| Servir la bóveda | `tokenization-vault`, DynamoDB, Redis, crypto, secretos y auditoría estricta | Tabla dedicada de la capacidad |
| Validar PAN en la bóveda | `tokenization-pan` | Perfil `pan.luhn` sobre el mismo motor |
| Cifrar e indexar un campo sin token | [Protección de campos](proteccion-de-campos.md) | Tabla de la aplicación |

El cliente remoto no necesita SDK AWS/Redis, claves de cifrado ni motor de bóveda. Los starters
de proveedor componen sus conexiones y ciclo de vida por separado. No instalar cliente y
servidor en un mismo servicio. El catálogo rechaza composiciones incompletas y ArchUnit comprueba
el alcance `CLIENT`/`VAULT`, junto con el alcance de datos de tarjeta.

## Cliente

Agregar `tokenization` y las capacidades de seguridad y cliente HTTP. Configurar un cliente
nombrado con TLS, JWT y sin reintentos automáticos:

```yaml
axiz:
  tokenization:
    mode: remote
    remote:
      client: token-vault
  http-client:
    clients:
      token-vault:
        base-url: https://tokens.example.internal
        auth:
          type: TOKEN_RELAY
        retry: NONE
        max-attempts: 1
        max-concurrent-calls: 32
        max-connections: 32
        connect-timeout: 2s
        read-timeout: 5s
        total-timeout: 10s
```

En servlet se inyecta `Tokenizer`; en WebFlux, `ReactiveTokenizer`. La identidad se obtiene
por invocación/suscripción, no se conserva en un singleton. `CLIENT_CREDENTIALS` sirve para
identidad de aplicación; requiere token URI y referencia a secreto en el cliente HTTP. No
sustituye la autorización del servidor. La fachada adicional `AsyncTokenizer` requiere ese
modo de identidad; para propagar el JWT de una suscripción se usa la fachada Reactor.

```java
try (var value = SensitiveValue.of("00123456")) {
  var result = tokenizer.tokenize(
      new TokenType("DNI"), value, new TokenDomain("billing"), UUID.randomUUID().toString());
  repository.saveToken(result.token().reveal());
}
```

El ejemplo usa un DNI sintético. Cerrar el valor después de terminar la operación. En Reactor
mantenerlo vivo hasta la suscripción y liberar en éxito, error o cancelación mediante `using`.
`detokenize` también devuelve un valor cerrable. No registrar cuerpos, índices ni tokens completos.
Las copias en `String`, HTTP, TLS o instrumentación externa no pueden borrarse de forma garantizada.

## Tipos y políticas del servidor

Cada tipo define perfil/version, keyset, dominios, propósitos, retención y metadatos. `dni.pe`
v1 exige ocho dígitos ASCII y conserva ceros iniciales; `text.identity` v1 conserva el texto.
`pan.luhn` v1 pertenece al módulo PAN y requiere `metadata-keys: [last4]`. Un tipo nuevo
registra un `TokenProfile` explícito; no se deduce el tipo del nombre de un campo ni del cliente.

```yaml
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
        PAN:
          profile: pan.luhn
          version: 1
          keyset: cards
          domains: [payments]
          purposes: [authorization]
          retention: 30d
          metadata-keys: [last4]
```

Estos valores ilustran el formato; no constituyen una política productiva universal.
`TokenAccessPolicy` añade las restricciones del negocio. El motor exige además las autoridades
`tokens:tokenize`, `tokens:lookup`, `tokens:describe`, `tokens:detokenize` o `tokens:delete`, según
la operación. Un tenant enviado en el JSON no cambia la identidad. No hay bypass de auditoría,
cuotas ni autenticación para el tipo PAN. No tokenizar CVV, PIN ni datos de autenticación sensibles.

Las cinco rutas son POST: `/v1/tokens`, `/lookup`, `/describe`, `/reveal` y `/delete` bajo
`/v1/tokens`. Aceptan JSON UTF-8 acotado; rechazan campos desconocidos, claves repetidas, query
strings, compresión e `Idempotency-Key`. Exigen HTTPS y producen `Cache-Control: no-store`,
incluidos errores de seguridad. La excepción HTTP local requiere un perfil de pruebas explícito.
`operationId` es correlación, no idempotencia ni autorización.

Una respuesta `AXZ-TOKEN-0504` significa resultado incierto: conciliar antes de repetir. Una
falla de auditoría después de escribir no borra la asociación para simular rollback. Los errores
no incluyen mensajes remotos ni causas. `429` conserva un `Retry-After` acotado.

## Infraestructura y operación

El servidor requiere DynamoDB transaccional de una región, KMS con rutas de clave inmutables,
HMAC independiente, Redis autenticado con presupuestos compartidos y publicación estricta de
auditoría. La tabla usa `pk` string, sin TTL, índices secundarios ni Global Tables. El arranque
valida esquema y controles C/E, pero no crea infraestructura ni acredita permisos de escritura.

El adaptador DynamoDB incluye `infra/vault.cloudformation.json`, guía operativa y simulación IAM.
Separar identidad operativa, mantenimiento y retención; el consumidor no recibe permisos sobre
la tabla o las claves. PITR/SSE no sustituyen el cifrado del campo. Las políticas de KMS deben
usar los contextos enrutados completos, incluyendo la ruta y el propósito codificado.

`VaultRotator` migra HMAC y sobres por lotes con barreras de versión, reservas permanentes de
identificadores y auditoría confirmada. Mantener lectores antiguos hasta terminar la migración
y comprobar referencias. `VaultRetention` elimina registros vencidos e índices mediante la misma
transacción condicionada, sin descifrar; respeta autorización por fila y límites de página/tiempo.
No reutilizar identificadores retirados. Respaldos y restauraciones necesitan su propio plan de
retención y conciliación; una restauración no habilita valores que debían estar eliminados.

## Verificar

Desde `axiz-framework`, con Docker disponible:

```sh
mvn -Pquality -pl quality/axiz-store-integration-tests -am \
  -Dtest=VaultAcceptanceIT,LocalStackTokenVaultIT,ValkeyTokenBudgetIT,ValkeyTokenBudgetSpringIT \
  -Dsurefire.failIfNoSpecifiedTests=false -Daxiz.it.requireDocker=true test
```

La aceptación combina JWT firmado, ambos stacks, DynamoDB/KMS local, Redis autenticado y SQS.
Verifica aislamiento, cuotas, repetición, revelación, errores y auditoría sin valores completos.
LocalStack no prueba IAM real. `verify_iam.py` requiere credenciales y permisos AWS; tampoco
sustituye pruebas de los roles y recursos finales. La capacidad no certifica PCI DSS por sí sola.
