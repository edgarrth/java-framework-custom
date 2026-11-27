<!-- generated -->
# axiz-structure-layered-api

Estilo en capas para servicios CRUD simples: controller, service y repository (contratos, Java simple).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-layered-api</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `CommandService` | Operaciones de escritura de un recurso. |
| `CrudService` | Operaciones de lectura y escritura de un recurso simple (catálogos, parámetros, tablas de mantenimiento). |
| `QueryService` | Operaciones de lectura de un recurso. |

Los dobles de prueba como `pe.axiz.test.support.layered.InMemoryCrudService` los proporciona
`axiz-test-support`; ese módulo se declara con scope test.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl structures/layered/axiz-structure-layered-api verify`.
