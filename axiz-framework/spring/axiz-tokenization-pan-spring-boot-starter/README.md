# axiz-tokenization-pan-spring-boot-starter

Starter sin código para habilitar el perfil PAN en una bóveda genérica.
No selecciona proveedor de almacenamiento ni da permisos de revelación.
Consultar [configuración y pruebas](../axiz-tokenization-pan-spring-boot-autoconfigure/README.md).

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-tokenization-pan-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-tokenization-pan-spring-boot-autoconfigure`](../axiz-tokenization-pan-spring-boot-autoconfigure/README.md).

## Uso desde el catálogo

```bash
axiz add tokenization-pan
```

Capacidades requeridas: `tokenization-vault`.

La [guía de tokenización](../../../axiz-docs/docs/guias/tokenizacion.md) describe los límites entre
cliente y bóveda, los perfiles, la autorización, la retención y el flujo independiente de protección
de campos.
