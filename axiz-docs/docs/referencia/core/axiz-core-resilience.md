<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-resilience

Políticas de reintento con backoff y jitter, decisiones de reintento que respetan la idempotencia y timeouts.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-resilience</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `Backoff` | Backoff exponencial con jitter completo: `random(0, min(maxDelay, initial * multiplier^n))`. |
| `Retry` | Ejecuta una operación con una política de reintento. |
| `RetryDecider` | Decide si una llamada fallida puede repetirse. |
| `RetryPolicy` | Cómo se reintenta una operación. |
| `TimeoutSettings` | Timeouts que todo cliente saliente debe definir (no hay un valor "infinito" por defecto). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-resilience verify`.
