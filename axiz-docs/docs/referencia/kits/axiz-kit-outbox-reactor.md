<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-kit-outbox-reactor

Contrato Reactor, independiente del proveedor, para escrituras transaccionales en el outbox.

Este módulo es dueño de la API `pe.axiz.kit.reactor.outbox`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-kit-outbox-reactor</artifactId>
</dependency>
```

## Tipos

Tipos públicos que declara este módulo:

- `ReactiveOutboxWriter`

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl kits/axiz-kit-outbox-reactor -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
