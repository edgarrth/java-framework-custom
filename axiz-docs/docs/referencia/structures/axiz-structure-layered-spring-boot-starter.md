<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-structure-layered-spring-boot-starter

Estilo en capas para servicios CRUD simples: controller, service y repository.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-layered-spring-boot-starter</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `CrudController` | Endpoints REST de un recurso simple sobre un `CrudService`. |
| `JpaCrudService` | `CrudService` sobre un repositorio Spring Data JPA. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl structures/layered/axiz-structure-layered-spring-boot-starter verify`.

## Borrado condicional

`CommandService.delete(id, expectedVersion)` requiere la versión que el cliente leyó previamente.
`CrudController` la acepta como un único valor fuerte y entre comillas en `If-Match`, por ejemplo
`If-Match: "3"`. Las cabeceras ausentes o inválidas devuelven 400; una versión desfasada devuelve 412
y conserva el recurso. Se rechazan los comodines, los validadores débiles y los múltiples. La
autorización de propiedad se ejecuta antes de comparar la versión, y el bloqueo optimista de JPA
también protege las escrituras transaccionales concurrentes. La actualización conserva la versión
esperada en su DTO de petición. Los clientes obtienen la versión desde el DTO de respuesta de su
aplicación; el controlador genérico no inventa un ETag para DTO arbitrarios.
