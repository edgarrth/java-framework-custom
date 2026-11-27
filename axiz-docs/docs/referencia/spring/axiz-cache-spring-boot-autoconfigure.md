<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-cache-spring-boot-autoconfigure

@AxizCacheable con Caffeine como L1 y Redis (ElastiCache) como L2, TTL por caché y tolerancia a fallos (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-cache-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizCacheAutoConfiguration` | `AxizCacheable` y cachés con nombre sobre L1 (Caffeine) más L2 (ElastiCache) cuando está configurado. |
| `AxizCacheCaffeineAutoConfiguration` | L1 sobre Caffeine cuando está en el classpath (lo incorpora el starter). |
| `AxizCacheProperties` | Caché de dos niveles (`axiz.cache.*`): L1 en memoria por instancia, L2 en ElastiCache (Redis o Valkey) compartido por las réplicas. |
| `AxizCacheableInterceptor` | Aplica `AxizCacheable`: la clave es la firma del método, el alcance (tenant, llamador) y los argumentos; los valores se guardan como JSON con su tipo genérico completo; los resultados `null` no se cachean; los resultados `Mono` y `Flux` se cachean al completarse, sin bloquear el event loop. Se ejecuta dentro de todo interceptor de seguridad de métodos (`ORDER`), de modo que la autorización se comprueba en cada acierto. |
| `AxizCaches` | Cachés con nombre sobre el almacén compartido de dos niveles, con el TTL configurado por nombre. |
| `CacheCallerResolver` | Identidad del llamador de una caché `CALLER`; un servicio con su propio tipo de autenticación declara uno. |
| `CaffeineByteCache` | L1 sobre Caffeine con TTL por entrada y desalojo por tamaño. |
| `DefaultCacheCallerResolver` | Identidad de los llamadores con token: emisor, sujeto y cliente del token; cualquier otra autenticación no se cachea. |
| `JacksonValueCodec` | Valores JSON con el mapper de la aplicación. |

Tipos relacionados: `AxizCacheRedisAutoConfiguration` están en `axiz-cache-redis-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-cache-spring-boot-autoconfigure verify`.
