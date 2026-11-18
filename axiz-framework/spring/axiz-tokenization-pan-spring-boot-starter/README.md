# axiz-tokenization-pan-spring-boot-starter

Starter sin código para habilitar el perfil PAN en una bóveda genérica.
No selecciona proveedor de almacenamiento ni da permisos de revelación.
Consultar [configuración y pruebas](../axiz-tokenization-pan-spring-boot-autoconfigure/README.md).

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-tokenization-pan-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-tokenization-pan-spring-boot-autoconfigure`](../axiz-tokenization-pan-spring-boot-autoconfigure/README.md).

## Catalog usage

```bash
axiz add tokenization-pan
```

Required capabilities: `tokenization-vault`.

See [tokenization](../../../axiz-docs/docs/guias/tokenizacion.md) for client/vault boundaries,
profiles, authorization, retention and the independent field-protection flow.
