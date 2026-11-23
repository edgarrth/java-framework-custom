<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-storage-s3

Almacenamiento de objetos en Amazon S3 con SSE-KMS y URL prefirmadas.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-storage-s3</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `S3AsyncObjectStorage` | Streaming nativo con S3AsyncClient, cancelación explícita y listado secuencial acotado. |
| `S3ObjectStorage` | Almacenamiento en Amazon S3: carga y descarga en streaming, SSE-KMS con la clave configurada y URL prefirmadas acotadas por un TTL máximo. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-storage-s3 verify`.

## Transporte asíncrono nativo

Se usa `presignUpload` cuando un PUT se delega a un cliente HTTP. Se aplican su URI y todas las
cabeceras devueltas, incluidas SSE-KMS y el tipo de contenido. El puerto siempre devuelve
instrucciones de carga completas. El cliente HTTP deriva `Host` a partir de la URI.

`S3AsyncObjectStorage` implementa el puerto `AsyncObjectStorage`, que solo depende del JDK. Sus
puentes privados traducen JDK Flow y el protocolo del SDK sin Reactor ni agregación de objetos.
Cada fragmento entregado tiene propiedad independiente. Cerrar una descarga no consumida cancela
su cuerpo y libera su conexión. El listado es dueño de la petición exacta del SDK para la página
activa y la cancela; usa un trampolín para que las páginas ya completadas no consuman la pila de Java.

Ambos adaptadores comparten la validación de claves, los metadatos, los checksums, SSE-KMS y la
política de prefirmado. La validez máxima de la URL debe estar entre un segundo y siete días; la
validez por llamada es positiva y no supera ese máximo. Los objetos inexistentes devuelven vacío;
los errores de bucket y de permisos se propagan.

Los clientes y el prefirmador se toman prestados. Se construyen al arrancar con `AsyncAwsClients`,
con región explícita, transporte Netty nativo, refresco de credenciales en segundo plano y un
`credentialsProvider()` de solo instantánea para el prefirmador. Nunca se construye una cadena de
credenciales por defecto en cada petición.
