<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-storage-spring-boot-autoconfigure

Almacenamiento de objetos en Amazon S3 con SSE-KMS y URLs prefirmadas (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-storage-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

`ReactiveStorage.presignUpload` difiere la creación de las instrucciones PUT firmadas hasta la suscripción;
el llamador debe enviar todas las cabeceras devueltas junto con la URI devuelta.

| Tipo | Propósito |
|---|---|
| `ReactiveStorage` | Operaciones nativas diferidas y liberación automática de la respuesta mediante content(key). |
| `AxizStorageAutoConfiguration` | Almacenamiento en memoria para pruebas y ejecuciones locales (`axiz.storage.type=memory`). |
| `AxizStorageProperties` | Almacenamiento de objetos (`axiz.storage.*`). |

Tipos relacionados: `AxizStorageS3AutoConfiguration` están en `axiz-storage-s3-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-storage-spring-boot-autoconfigure verify`.

## Pila de ejecución

Las aplicaciones servlet reciben `ObjectStorage` y `S3Client`. Las aplicaciones reactivas reciben
`AsyncObjectStorage`, `ReactiveStorage` y `S3AsyncClient`; no se crea ningún bean de almacenamiento
síncrono. Aportar un puerto nativo hace que no se creen los recursos S3 por defecto. Aportar tanto el
cliente como el presigner también evita crear credenciales por defecto. Un cliente nativo y un presigner
por defecto comparten credenciales propias precalentadas y liberan sus recursos al cerrar el contexto.
Los clientes y firmantes personalizados deben conservar el mismo contrato no bloqueante de credenciales y
transporte.

```yaml
axiz:
  aws:
    region: us-east-1
  storage:
    type: s3
    bucket: reports
    max-presign-ttl: 15m
```

`ReactiveStorage.content(key)` libera una respuesta al completarse, ante un error y ante la cancelación.
`get(key)` transfiere esa responsabilidad a su llamador. Todas las operaciones de la fachada se difieren
hasta la suscripción. `type=memory` usa la implementación nativa acotada solo en entornos local/test.
