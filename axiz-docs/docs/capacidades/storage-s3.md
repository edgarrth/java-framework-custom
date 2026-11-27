<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# storage-s3

Almacenamiento de objetos en S3 (streaming, URLs prefirmadas, metadatos), con observaciones

```bash
axiz add storage-s3
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-storage-s3-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`storage`](storage.md) |
| Incluida por defecto en | batch |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.storage.bucket: ${AXIZ_STORAGE_BUCKET:local-bucket}
```

Perfil `local` (`application-local.yaml`):

```yaml
axiz.aws.endpoint: http://localhost:4566
spring.cloud.aws.endpoint: http://localhost:4566
spring.cloud.aws.region.static: us-east-1
```

## Llaves del ConfigMap por ambiente

- `axiz.storage.bucket`

## Uso

```java
storage.put("in/file.txt", content, ObjectMetadata.of("text/plain", length));
// Servlet: ObjectStorage + InputStream. Reactivo: ReactiveStorage + Publisher<ByteBuffer>, devuelve Mono<Void>.
// Las descargas se consumen con reactiveStorage.content(key) para liberar las respuestas al terminar, fallar o cancelar.
```

## Módulos

- [`axiz-storage-s3-spring-boot-autoconfigure`](../referencia/spring/axiz-storage-s3-spring-boot-autoconfigure.md)
- [`axiz-core-storage`](../referencia/core/axiz-core-storage.md)
