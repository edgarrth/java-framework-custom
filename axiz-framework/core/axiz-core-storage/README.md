<!-- generated -->
# axiz-core-storage

Object storage port with streaming, presigned URLs and metadata; in-memory implementation.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-storage</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AsyncObjectStorage` | Native asynchronous port using CompletionStage and JDK Flow for streaming. |
| `AsyncStoredObject` | Single-subscription body; consume or close to release its connection. |
| `InMemoryAsyncObjectStorage` | Local/test implementation; uploads have a declared, bounded size (16 MiB by default). |
| `InMemoryObjectStorage` | In-memory storage for tests and local runs. |
| `ObjectKey` | Validated object key: relative, no `..` segments, no control characters. |
| `ObjectMetadata` | Metadata of a stored object. |
| `ObjectStorage` | Object storage port (Amazon S3 in production). |
| `PresignedUpload` | URI and immutable required HTTP headers for a signed upload; redacts credentials in diagnostics. |
| `StoredObject` | Streamed object content; close it to release the connection. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-storage verify`.

## Streaming contract

Its public flow contracts use JDK types; the module follows the core dependency exceptions for JSpecify and shared core types. Upload publishers must honor demand and support replay if the
remote backend retries. A cancelled operation attempts to cancel the active native request; it
cannot undo a write already committed by the backend. Download chunks are read-only and remain
valid after delivery. Always consume or close a returned `AsyncStoredObject`, even if no body
subscription has started. Local memory storage is process-local and never a production fallback.
