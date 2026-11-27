<!-- generated -->
# axiz-structure-bff-api

Backend for frontend: composición en paralelo, resultados parciales, adaptación por canal y caché de lectura (contratos, Java simple).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-bff-api</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ChannelAdapter` | Da forma a un resultado compuesto para un canal (móvil, web, socio, etc.). |
| `ChannelAdapters` | Elige el adaptador del canal del llamador y, si no existe, usa el adaptador por defecto (`*`). |
| `Composer` | Llama a varias dependencias en paralelo sobre hilos virtuales y compone el resultado. |
| `Composition` | Resultado de una ejecución de `Composer`. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl structures/bff/axiz-structure-bff-api verify`.
