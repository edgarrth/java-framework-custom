<!-- generated -->
# axiz-core-cache

Puerto de caché, caché de dos niveles e implementación en memoria.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-cache</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizCache` | Fachada tipada sobre un `ByteCache`: prefija las claves (`<service>:<cache>:<key>`), serializa con un `ValueCodec` y carga cada valor ausente una sola vez por clave a la vez (single flight) para evitar estampidas. |
| `AxizCacheable` | Guarda en caché el resultado de un método (incluidos `Mono` y `Flux` en servicios reactivos). |
| `ByteCache` | Nivel de caché a nivel de bytes (memoria local, Redis). |
| `CacheKeys` | Construye claves de caché desde los argumentos del método; los valores con separadores se escapan. |
| `CacheUnavailableException` | El nivel remoto de la caché no está disponible. |
| `InMemoryByteCache` | Nivel local con TTL y tamaño acotado (al llenarse se descartan primero las entradas más antiguas). |
| `TwoLevelCache` | Caché L1 (local) más L2 (Redis). |
| `ValueCodec` | Serializa los valores guardados (JSON con el mapper del servicio en el starter). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-cache verify`.
