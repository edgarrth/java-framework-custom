<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-api

Respuestas de API, paginación y contratos de cabeceras.

Este módulo es dueño de la API `pe.axiz.core.api`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-api</artifactId>
</dependency>
```

## Tipos

Tipos públicos que declara este módulo:

- `ApiResponse`
- `HeaderCatalog`
- `HeaderDefinition`
- `HttpPayloadPolicy`
- `Page`
- `PageMetadata`
- `PageRequest`
- `PageResponse`
- `Sort`
- `StandardHeaders`

## Límites

El core es dueño de tipos de valor, políticas o puertos y no realiza I/O de red ni de sistema de
archivos. La composición con Spring y los proveedores concretos pertenecen a módulos separados.
Los consumidores se prueban contra estos contratos sin arrancar Spring.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl core/axiz-core-api -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
