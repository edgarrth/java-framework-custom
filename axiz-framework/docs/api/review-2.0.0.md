# Public API review for the initial 2.0.0 release

Review date: 2026-11-18. The inventory contains 241 published JAR coordinates. The initial module
review added 90 coordinates and changed 57 signature sets relative to the older design snapshot.
The subsequent integral-review corrections change 19 signature sets, with no coordinates added
or removed from the baseline commit `de594c9ed37b`.
This is a review of the initial API, not a claim of compatibility with a published baseline.
The GitHub publication gate must still verify the actual registry before allowing first-release mode.

## Accepted changes and consumer impact

| Area | Contract and migration |
|---|---|
| Core responsibilities | API, context, concurrency and JSON types move out of `axiz-core-commons` into dedicated artifacts. SQL identifiers move to `axiz-kit-sql`. Add the artifact that owns the types used by the consumer; do not depend on an umbrella module to acquire them. |
| Provider isolation | AWS, Kafka/MSK, Redis, S3, KMS, file secrets and individual persistence providers have separate autoconfigure/starter artifacts. Neutral configuration properties no longer expose provider records. Select the provider starter and its own properties explicitly. New provider starters contain no public Java implementation classes. |
| External I/O | JDK HTTP clients and filesystem/environment secret implementations move to adapters. Core HTTP and SOAP use transport ports. Reactor-specific audit/outbox contracts belong to kits. |
| Construction and lifecycle | Large positional constructors become builders. Closeable secret values, caches, clients and decorators declare ownership and cleanup. Removed global encryption/auditing registries are replaced by injected collaborators; converters cannot be constructed without the encryptor they require. |
| Audit and transactions | Integrity, sealing, checkpoint and strict-delivery contracts are explicit. Transactional outbox and audit ports expose transaction-resource identity so composition can reject mismatched resources. Event metadata distinguishes delivery and persistence outcomes. |
| Sensitive data | Typed card data, masking and HTTP payload policies prevent accidental serialization and logging. `OUTCOME_UNKNOWN` is distinct from a confirmed failure. Reveal permissions and JWT access profiles are explicit configuration. |
| Independent field protection | `FieldProtector` exposes encrypt, index, candidate-index lookup, decrypt and composed protect operations. `ProtectedField` returns ciphertext and keyed index for consumer-owned storage; this capability does not acquire the token vault. |
| Tokenization | The client returns token, type, creation status and restricted metadata. Ciphertext and equality fingerprints remain inside the vault. Lookup returns an existing token. Server operations require an authenticated caller and separate persistence, rate-budget, audit and reconciliation ports. PAN policy is a separate capability. |
| Application structures | Layered CRUD separates request, response and entity types. The in-memory CRUD implementation moves to test support. Code generation exposes architecture/card-data/tokenization scopes and validates coherent combinations. |
| Test support | Container images are selected through the canonical image factory instead of public static image constants. ArchUnit exposes card-data, tokenization, money, transaction and state rules; its self-check fixture is deliberately part of the quality artifact. |

These changes include source and binary breaks from the unpublished design snapshot. A consumer
compiled against that snapshot must regenerate or migrate and recompile. Do not apply this
inventory as an override for a previously published 2.0.0; publication discovery rejects that case.

## Evidence and limits

The tracked `signatures/*.txt` files are extracted from actual JARs with
`javap -protected -constants`. `public-api.json` records their SHA-256 values and the inspected
JAR hashes. The gate verifies the signature files themselves as well as the rebuilt API.
JAR hashes identify review inputs; API comparison does not require byte-identical build metadata.

Review included the changed signatures, module ownership, newly exposed provider types and the
separation between field protection, client tokenization and vault administration. Automated
unit, integration, generated-consumer and architecture checks remain separate release gates.
This review does not certify PCI DSS, external IAM behavior, deployment or remote publication.

The local GitHub token currently cannot enumerate Maven Packages (`403`, missing `read:packages`).
Consequently, updating this reviewed inventory does not mark the release gate as passed and does
not establish that the publication registry is empty.

The DynamoDB kit also exposes `EncryptedValueAttributeConverter`: it serializes an
already encrypted envelope and rejects plaintext or non-string attributes. Encryption
and row binding remain the responsibility of `FieldEncryptor` or `FieldProtector`;
the converter does not own keys or silently encrypt values. Both DynamoDB demo
repositories use this shared codec.

## Composición Redis de tokenización

En la candidata inicial 2.0.0, `RedisTokenBudget` y `RedisAsyncTokenBudget` reciben
un quinto argumento `RateLimiter`/`AsyncRateLimiter`. La composición se traslada
al módulo Spring proveedor, que usa una conexión Redis dedicada y fallo cerrado.
Esto elimina la dependencia de producción entre adaptadores de capacidades distintas
sin duplicar el algoritmo de ventanas. Los consumidores de los starters no cambian;
los constructores programáticos deben inyectar el limitador correspondiente.
La integración real valida agotamiento, aislamiento, caída del almacén y cancelación.

## Correcciones de concurrencia y recuperación de la candidata

La revisión integral incorpora estos contratos antes de publicar la primera 2.0.0:

- Saga exige dueño en las actualizaciones persistidas; desaparece la variante sin propietario.
  El participante recibe identidad de ejecución y fence y debe aplicarlos al efecto externo.
  `notApplied` permite observar de nuevo; un rechazo duradero se expresa con `rejected`.
  Los consumidores programáticos deben migrar sus stores y participantes, además de actualizar
  el esquema JDBC de propiedad. La implementación en memoria usa un monitor privado.
- La recuperación de presupuestos Redis expone propietario, encarnación, operación e inventario.
  Conciliar exige prueba externa de terminación/cercado y un journal; la edad o muerte de una JVM
  no autorizan por sí solas a liberar una reserva. Los nuevos overloads conservan los constructores
  existentes y permiten aportar identidad de operación explícita.
- `AsyncIdempotencyCas` reúne el protocolo compartido. Los proveedores remotos deben delegar con
  un reloj fresco en cada intento; `false` significa resultado no confirmado, incluida contención.
  No constituye permiso para repetir un efecto ni prueba suficiente de pérdida de propietario.
- Inbox exige retención mayor que lease y rechaza marcar procesado al vencer el lease, incluso
  sin takeover. No garantiza cercado de efectos externos ni retención desde la finalización.
- `WorkerStatus` y los snapshots de `WorkerLifecycle` permiten comprobar salud sin exponer tareas
  ni excepciones. `axizWorkers` se integra en readiness por defecto cuando Boot health está presente.
  Grupos personalizados deben conservar ese miembro si requieren esa garantía.
  `InboxPurger` es final: su extensión se realiza componiendo el puerto de almacenamiento, sin
  permitir un finalizador de subclase sobre una instancia cuya validación de construcción falló.
- Los records `AxizSagaProperties` y `AxizOutboxProperties.Relay` agregan `executionLease` y
  `shutdownGrace`: los constructores posicionales cambian; la configuración Spring y el builder
  de Relay aportan los valores predeterminados.
- El grace predeterminado de outbox/SQS es 15s; Spring valida un margen adicional de 5s contra
  el timeout por fase. La configuración que antes excedía ese presupuesto ahora falla al arrancar.
  Los charts conceden 90s y quien agregue fases o hooks debe ajustar el presupuesto total.
- BlindIndex y PanDetector admiten buffers `char[]`; CheckDigits admite `CharSequence`.
  Conservan las variantes String y el formato de los índices; el consumidor decide la propiedad
  y borrado de sus buffers.
- JDBC/R2DBC separan el rechazo definitivo de commit del resultado desconocido; un cleanup
  posterior no transforma un commit confirmado en un fallo reintentable. La API de transacción
  independiente de R2DBC permite que una secuencia no reutilice identificadores tras rollback externo.

Estos cambios se aceptan sobre la candidata no publicada. No se declara compatibilidad binaria
con instantáneas de diseño anteriores ni se omite la comprobación del registro remoto.

## Cierre AXV-01–20 (19 de noviembre de 2026)

Se revisaron las diferencias de firmas de diez artefactos contra los JAR compilados:

- `CommittedTransactionOutcome`, `POST_COMMIT_PUBLICATION` y `PostCommitPublicationException`
  distinguen un commit confirmado cuya publicación posterior falló. Los adaptadores no deben
  traducirlo a rollback ni autorizar la repetición del efecto de negocio.
- `UncertainTokenOperation.Reason.COMMITTED_AUDIT_UNCONFIRMED` distingue una creación o
  eliminación persistida cuyo registro de auditoría no confirmó. Exige conciliación y no autoriza
  repetir el efecto; los consumidores con switches exhaustivos deben contemplar la nueva razón.
- `IdempotencyLease.start` admite un presupuesto explícito para renovación. Conserva el overload
  anterior; un timeout produce incertidumbre y no prueba que el proveedor haya cancelado el trabajo.
- `SoapClient.call` acepta credenciales por operación. `SoapEnvelope.Credentials` implementa
  `AutoCloseable` y sincroniza acceso/borrado del buffer. El llamador conserva la responsabilidad
  de cerrar las credenciales que construye; `SoapClients` lo hace para las que resuelve.
- `AxizSoapProperties.Client` incorpora `sslBundle` y construcción por opciones nombradas;
  conserva el constructor anterior. `SoapClients` acepta un registro de bundles opcional.
  La credencial WS-Security se resuelve por llamada; cambiar certificados requiere recrear
  la fábrica o reiniciar gradualmente, no se promete recarga automática del transporte.
- `SchemaInitializer` admite el mapa de nombres SQL validados manteniendo el constructor original.
- Las imágenes de prueba se crean mediante fábricas: se retira `AxizContainers.LOCALSTACK`
  y se usa `AxizContainers.localstack()`/`TestContainerImages.localstack().withServices(...)`.
  `PatchedRyukImageSubstitutor` es el SPI público requerido por Testcontainers para cargar el
  recolector corregido; no es una capacidad de producción del core.

El inventario sólo actualiza esos diez contratos revisados. La verificación autenticada de
GitHub Packages sigue siendo obligatoria para confirmar que corresponde al primer release.
