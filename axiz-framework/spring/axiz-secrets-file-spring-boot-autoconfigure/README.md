# axiz-secrets-file-spring-boot-autoconfigure

Integración de archivos para la resolución de secretos.

Este módulo es dueño de la API `pe.axiz.spring.secrets.file`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-secrets-file-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizSecretsFileAutoConfiguration`
- `AxizSecretsFileProperties`
- `SecretsConfigTreeEnvironmentPostProcessor`

## Configuración

Prefijos de propiedades propios: `axiz.secrets.*`.

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

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-secrets-file-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
