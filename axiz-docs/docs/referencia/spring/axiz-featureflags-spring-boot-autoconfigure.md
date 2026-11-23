<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-featureflags-spring-boot-autoconfigure

Feature flags desde ConfigMap o Parameter Store expuestos mediante OpenFeature (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-featureflags-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizFeatureFlagsAutoConfiguration` | Feature flags desde la configuración: activación por canal, tenant, atributos o porcentaje. |
| `AxizFeatureFlagsProperties` | Feature flags (`axiz.feature-flags.flags.<name>`) desde el ConfigMap del servicio o Parameter Store (ambos llegan al entorno como propiedades). |
| `AxizOpenFeatureAutoConfiguration` | Registra el proveedor axiz en OpenFeature y expone un `Client`. |
| `AxizOpenFeatureProvider` | Proveedor OpenFeature respaldado por los flags configurados. |
| `FlagContexts` | Contexto de evaluación de la petición actual: usuario (o cliente) como clave de segmentación, canal, tenant. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-featureflags-spring-boot-autoconfigure verify`.
