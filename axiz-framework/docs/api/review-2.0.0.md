# Public API review for the initial 2.0.0 release

Review date: 2026-11-18. The inventory contains 241 published JAR coordinates: 90 additions,
57 changed signature sets and no removed coordinates relative to the previous design snapshot.
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
