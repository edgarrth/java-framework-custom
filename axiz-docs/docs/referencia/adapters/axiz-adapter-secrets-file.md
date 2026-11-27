<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-secrets-file

Adaptadores de secretos desde archivos y variables de entorno, con refresco acotado y ciclo de vida propio.

Este módulo es dueño de la API `pe.axiz.adapter.secrets.file`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-secrets-file</artifactId>
</dependency>
```

## Tipos

Tipos públicos que declara este módulo:

- `EnvironmentAsyncSecretResolver`
- `EnvironmentSecretResolver`
- `FileSecretResolver`
- `RefreshingFileSecretResolver`

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl adapters/axiz-adapter-secrets-file -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
