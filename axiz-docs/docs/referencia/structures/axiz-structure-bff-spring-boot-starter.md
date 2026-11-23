<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-structure-bff-spring-boot-starter

Backend for frontend: composición en paralelo, resultados parciales, adaptación por canal y caché de lectura.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-bff-spring-boot-starter</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizBffAutoConfiguration` | Estilo BFF: composición en paralelo que conserva el contexto. |
| `Compositions` | Crea instancias de `Composer` cuyas tareas en paralelo llevan el contexto del llamador (metadatos de la petición, tenant, MDC, traza) mediante context-propagation de Micrometer. |
| `ReactiveComposer` | Composición reactiva para BFF con WebFlux: las partes se suscriben en paralelo, las partes opcionales usan su alternativa ante error o timeout y nada bloquea. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl structures/bff/axiz-structure-bff-spring-boot-starter verify`.
