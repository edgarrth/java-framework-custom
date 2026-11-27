<!-- generated -->
# axiz-resilience-spring-boot-autoconfigure

Valores por defecto de Resilience4j (circuit breaker, bulkhead) y @Retryable de Spring configurados por propiedades (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-resilience-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizResilienceAutoConfiguration` | Registros de Resilience4j con las políticas de `axiz.resilience`, sus métricas de Micrometer y `@Retryable` / `@ConcurrencyLimit` de Spring para los métodos de la aplicación. |
| `AxizResilienceProperties` | Políticas de resiliencia (`axiz.resilience.*`). |
| `ResiliencePolicies` | Resuelve los componentes de resiliencia de una dependencia con nombre desde `axiz.resilience`. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-resilience-spring-boot-autoconfigure verify`.
