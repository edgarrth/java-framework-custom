<!-- generated -->
# axiz-adapter-cache-redis

Caché en Redis (ElastiCache/Valkey) con desalojo basado en SCAN y tolerancia a fallos.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-cache-redis</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `RedisByteCache` | Nivel de caché en Redis (ElastiCache o Valkey). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-cache-redis verify`.
