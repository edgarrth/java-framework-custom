<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-storage

Puerto de almacenamiento de objetos con streaming, URLs prefirmadas y metadatos; implementación en memoria.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-storage</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AsyncObjectStorage` | Puerto asíncrono nativo con CompletionStage y Flow del JDK para el streaming. |
| `AsyncStoredObject` | Cuerpo de una sola suscripción; se consume o se cierra para liberar su conexión. |
| `InMemoryAsyncObjectStorage` | Implementación para local y pruebas; las subidas tienen un tamaño declarado y acotado (16 MiB por defecto). |
| `InMemoryObjectStorage` | Almacenamiento en memoria para pruebas y ejecuciones locales. |
| `ObjectKey` | Clave de objeto validada: relativa, sin segmentos `..` y sin caracteres de control. |
| `ObjectMetadata` | Metadatos de un objeto guardado. |
| `ObjectStorage` | Puerto de almacenamiento de objetos (Amazon S3 en producción). |
| `PresignedUpload` | URI y cabeceras HTTP obligatorias e inmutables de una subida firmada; oculta las credenciales en los diagnósticos. |
| `StoredObject` | Contenido del objeto en streaming; se cierra para liberar la conexión. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-storage verify`.

## Contrato de streaming

Sus contratos públicos de flujo usan tipos del JDK; el módulo sigue las excepciones de dependencias
de core para JSpecify y los tipos compartidos de core. Los publicadores de subida respetan la
demanda y admiten repetición si el backend remoto reintenta. Una operación cancelada intenta cancelar
la petición nativa activa; no puede deshacer una escritura que el backend ya confirmó. Los bloques
de descarga son de solo lectura y siguen siendo válidos tras la entrega. Todo `AsyncStoredObject`
devuelto se consume o se cierra, aunque no se haya iniciado la suscripción al cuerpo. El
almacenamiento en memoria es local al proceso y nunca es un respaldo de producción.
