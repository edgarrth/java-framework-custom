<!-- generated -->
# axiz-core-featureflags

Feature flags segmentados por canal, tenant o porcentaje, con proveedor compatible con OpenFeature.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-featureflags</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `EvaluationContext` | Para quién se evalúa un flag. |
| `FeatureFlags` | Evalúa feature flags. |
| `FlagDefinition` | Un feature flag. |
| `FlagProvider` | Fuente de definiciones de flags (propiedades enlazadas desde el ConfigMap, Parameter Store). |
| `MapFlagProvider` | Flags desde la configuración (enlazada desde el ConfigMap del servicio). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-featureflags verify`.
