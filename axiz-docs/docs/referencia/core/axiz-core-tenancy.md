<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-tenancy

Contexto, resolución y propagación del tenant.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-tenancy</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `TenantContext` | Tenant de la unidad de trabajo actual, vinculado durante una llamada. |
| `TenantRequiredException` | La operación necesita un tenant, pero no hay ninguno vinculado. |
| `TenantResolver` | Resuelve el tenant de una petición entrante: primero desde el claim del token validado y, solo cuando se confía de forma explícita, desde una cabecera. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-tenancy verify`.
