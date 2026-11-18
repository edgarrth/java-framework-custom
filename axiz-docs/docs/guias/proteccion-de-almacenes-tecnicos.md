# Protección de cargas en almacenes técnicos

Outbox, sagas e idempotencia pueden cifrar el contenido que necesitan conservar para
recuperar una operación. Esta capacidad es independiente de la tokenización y de
`FieldProtector`: no crea tokens ni índices de búsqueda.

## Configuración

```yaml
axiz:
  outbox:
    payload-protection: envelope
    payload-read-timeout: 5s
  saga:
    context-protection: envelope
  idempotency:
    response-protection: envelope
```

Cada opción tiene valor predeterminado `none`; actívela cuando la carga contenga datos
sensibles. `envelope` exige `EnvelopeEncryptor` en servlet/JDBC y
`AsyncEnvelopeEncryptor` en la ruta reactiva. Puede obtenerlos de `crypto-kms` o aportar
una implementación de los puertos de claves. No hay claves de desarrollo, conversión
implícita entre stacks ni sustitución por texto claro si falta el proveedor: el arranque
falla. Las sagas son imperativas. Su persistencia JDBC se instala mediante
`axiz-saga-jdbc-spring-boot-starter`; la base permite aportar otro `SagaStateStore`.

## Qué se protege

| Capacidad | Contenido cifrado | Vínculo autenticado |
|---|---|---|
| Outbox | CloudEvent serializado | Destino e identificador del evento |
| Saga | Contexto serializado | Nombre de la saga e identificador de instancia |
| Idempotencia | Bytes de la respuesta | Operación, ámbito del solicitante, clave, estado HTTP y cabeceras reproducibles |

Los bytes se guardan con marca `AXZP` y versión 1; el formato textual usa `axzp1:` y
base64url canónico. El envelope aporta AES-256-GCM y la clave de datos envuelta.
Cambiar el vínculo o el contenido impide descifrar. Los metadatos necesarios para
consultar y coordinar el almacén siguen visibles: destinos, identificadores, estados,
fechas y cabeceras no son un lugar para PAN, DNI u otros datos sensibles.

El payload máximo es 16 MiB antes de cifrar, además de los límites propios de cada
capacidad y proveedor. El tamaño aumenta por el envelope y base64: revise el límite
final del registro, especialmente en DynamoDB. El modo `none` rechaza marcadores
protegidos y versiones reservadas. Desactivar el cifrado no devuelve ciphertext al
consumidor. `envelope` tampoco acepta filas antiguas sin protección.

## Transacciones, recuperación y concurrencia

Outbox cifra antes de insertar dentro de la transacción de negocio. La escritura
R2DBC conserva el contexto Reactor después de la respuesta asíncrona del proveedor
de claves. El relay descifra después del claim y antes de publicar. Su lease reserva
el plazo de lectura más el presupuesto de publicación, y vuelve a comprobar el tiempo
restante después de descifrar. Para R2DBC, el relay imperativo solo espera la operación
nativa en su hilo virtual propio, con timeout y cancelación; nunca en un event loop.
Un worker R2DBC sin entorno web debe aportar explícitamente el encryptor asíncrono.

Las sagas cifran tanto el contexto inicial como cada estado posterior; al reanudar
usan el identificador y nombre almacenados. El codec del contexto no contiene claves.

Idempotencia cifra una sola vez antes de completar el registro. `StoredResponseCodec`
serializa esos mismos bytes de forma determinista para conservar la comparación
atómica entre versiones. La lease se renueva hasta terminar cifrado y persistencia.
Un fallo al completar deja un resultado desconocido, no autoriza repetir el efecto de
negocio. Una respuesta que supera el límite de captura sigue la política existente:
se conserva como respuesta con cuerpo omitido y no se inventa un cuerpo para replay.

## Migración y operación

Habilitar o deshabilitar esta política con registros existentes requiere una migración
controlada por el dueño del servicio. Primero detenga escritores y recuperación,
transforme los registros con sus vínculos originales, verifique la lectura y después
active el nuevo modo. No se ofrece lectura mixta silenciosa. Mantenga las claves de
lectura hasta agotar la retención de todos los registros que las requieren; la rotación
del proveedor de claves no sustituye ese inventario.

No guarde CVV, PIN ni pistas en estos almacenes: cifrar datos prohibidos no autoriza su
persistencia. Los buffers propios se limpian al terminar, fallar o cancelar; Java no
permite garantizar el borrado físico de todos los Strings, copias del transporte o del
proveedor. El cifrado de estas cargas no constituye por sí solo una certificación PCI.

## Pruebas

Las pruebas `PayloadProtectionTest`, `OutboxPayloadProtectionTest`,
`SagaPayloadProtectionTest`, `IdempotencyEncryptionTest` y `PayloadWiringTest`
comprueban framing, manipulación, vínculos, replay, selección de proveedores y límites.
`PostgresOutboxOrderingIT#protectedPayloadCommitsAtomicallyAndOnlyRelayRevealsIt`
ejercita commit/rollback y lectura con PostgreSQL real por JDBC y R2DBC, con BlockHound.
Para las pruebas de integración configure Docker y `-Daxiz.it.requireDocker=true`;
una prueba omitida no cuenta como validación.
