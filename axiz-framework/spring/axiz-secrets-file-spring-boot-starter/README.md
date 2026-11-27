# axiz-secrets-file-spring-boot-starter

Fuentes de secretos en archivos.

Este módulo es dueño de la API `pe.axiz.spring.secrets.file.starter`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-secrets-file-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-secrets-file-spring-boot-autoconfigure`](../axiz-secrets-file-spring-boot-autoconfigure/README.md).

## Uso desde el catálogo

```bash
axiz add secrets-file
```

Capacidades requeridas: `secrets`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.secrets.directory: /var/run/secrets/axiz
```
