<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-lock

Puerto de lock distribuido con lease, renovación en segundo plano y plantilla de lock; implementación en memoria.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-lock</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `DistributedLock` | Lock distribuido con lease: un propietario caído nunca bloquea un recurso más allá de su lease. |
| `InMemoryDistributedLock` | Lock para un solo proceso (pruebas, ejecuciones locales). |
| `LockErrors` | Errores de la capacidad de lock. |
| `LockHandle` | Un lock adquirido. |
| `LockTemplate` | Serializa el trabajo sobre un recurso: espera el lock hasta `waitTimeout`, renueva el lease en segundo plano (hilo virtual, cada tercio del lease) mientras corre el trabajo y siempre lo libera. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-lock verify`.
