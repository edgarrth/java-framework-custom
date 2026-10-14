<!-- generated -->
# axiz-core-storage-s3

Amazon S3 object storage with SSE-KMS and presigned URLs.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-storage-s3</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `S3ObjectStorage` | Amazon S3 storage: streaming upload and download, SSE-KMS with the configured key, presigned URLs bounded by a maximum TTL. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-storage-s3 verify`.
