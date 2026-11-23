<!-- generated -->
# axiz-core-secrets

Puerto SecretResolver con resolvedores de entorno y de archivos y caché con TTL.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-secrets</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `CachingSecretResolver` | Guarda en caché los secretos resueltos durante un TTL para no llamar al almacén de secretos en cada uso, sin dejar de recoger las rotaciones. |
| `CompositeSecretResolver` | Prueba los resolvedores en orden; gana el primero que tiene el secreto. |
| `SecretNotFoundException` | Un secreto requerido no está disponible en ninguna fuente. |
| `SecretResolver` | Resuelve secretos por nombre. |
| `SecretValue` | Un valor secreto. |
| `AsyncSecretResolver` | Búsqueda asíncrona nativa, opcional y obligatoria, con cancelación de la fuente activa. |
| `CompositeAsyncSecretResolver` | Pasa a la siguiente fuente solo ante ausencia; los fallos detienen la cadena. |
| `CachingAsyncSecretResolver` | Cargas remotas acotadas y agrupadas; no retiene los valores ausentes ni los fallidos. |

Las peticiones reactivas usan `AsyncSecretResolver`. La búsqueda en archivos montados lee una
instantánea inmutable en memoria; el acceso al sistema de archivos ocurre al arrancar y en el worker
de refresco. El resolvedor de archivos se cierra junto con el ciclo de vida de la aplicación. Un
refresco fallido falla de forma cerrada hasta el siguiente refresco exitoso. Las instantáneas
admiten como máximo 1 000 archivos, 1 MiB por archivo y 16 MiB en total. Se rechaza el UTF-8 inválido.

Los nombres lógicos como `database#password` seleccionan un campo JSON en Secrets Manager. Las
fuentes de archivos convierten `/` y `#` en `.`, lo que permite archivos montados como
`database.password`. Las fuentes de entorno usan su conversión documentada a mayúsculas con guion
bajo. `SecretValue.reveal()` nunca se escribe en un log.

Tipos relacionados: `EnvironmentAsyncSecretResolver`, `EnvironmentSecretResolver`, `FileSecretResolver`, `RefreshingFileSecretResolver` están en `axiz-adapter-secrets-file`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-secrets verify`.
