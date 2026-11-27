<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-lock-redis

Lock distribuido en Redis.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-lock-redis</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `RedisDistributedLock` | Lock en Redis: `SET key token NX PX lease`; renovar y liberar son scripts Lua que actúan solo cuando el token guardado es el propio, de modo que un propietario vencido nunca toca el lock del siguiente. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-lock-redis verify`.
