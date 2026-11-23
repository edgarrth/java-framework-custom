<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-concurrent

Concurrencia acotada y composición asíncrona.

Este módulo es dueño de la API `pe.axiz.core.concurrent`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-concurrent</artifactId>
</dependency>
```

## Tipos

Tipos públicos que declara este módulo:

- `AsyncLoadingCache`
- `BoundedCallbackExecutor`
- `LeaseTiming`
- `Stages`

## Límites

El core es dueño de tipos de valor, políticas o puertos y no realiza I/O de red ni de sistema de
archivos. La composición con Spring y los proveedores concretos pertenecen a módulos separados.
Los consumidores se prueban contra estos contratos sin arrancar Spring.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl core/axiz-core-concurrent -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
