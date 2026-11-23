<!-- generated -->
# axiz-secrets-spring-boot-autoconfigure

SecretResolver sobre archivos montados, variables de entorno y AWS Secrets Manager o Parameter Store (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-secrets-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizSecretsAutoConfiguration` | `SecretResolver` sobre archivos montados, variables de entorno y los adaptadores AWS, con una caché con TTL. |
| `AxizSecretsProperties` | Resolución de secretos (`axiz.secrets.*`). |
| `SecretSource` | Fuente de secretos aportada por un adaptador; las fuentes se consultan en el orden de los beans, después de los archivos montados y del entorno. |
| `ReactiveSecrets` | Búsqueda nativa diferida; `resolve` completa vacío ante la ausencia y `require` falla explícitamente. |
| `AsyncSecretSource` | Fuente nativa ordenada aportada mediante un puerto core. |

En una aplicación reactiva se usa `ReactiveSecrets` o el puerto core `AsyncSecretResolver`. La prioridad
de las fuentes es la instantánea de archivos montados, el entorno y luego las fuentes remotas ordenadas
(Secrets Manager antes que SSM por defecto). Solo las búsquedas remotas usan la caché con TTL coalescida,
de modo que la rotación de archivos no suma un retraso de caché adicional. Las instantáneas montadas se
renuevan cada `max(1 second, axiz.secrets.cache-ttl)`; el TTL por defecto es de cinco minutos.

El resolvedor síncrono sigue disponible para los consumidores de bootstrap, como el cifrado de campos.
Un resolvedor asíncrono provisto por la aplicación reemplaza la cadena de fuentes nativas y sus recursos.
`axiz.secrets.enabled=false` desactiva tanto la configuración AWS síncrona como la nativa. Los clientes
propios y el renovador de archivos se cierran con el contexto de Spring; los clientes personalizados
conservan su propiedad.

Tipos relacionados: `AxizSecretsAwsAutoConfiguration`, `AxizSecretsNativeAwsAutoConfiguration` están en `axiz-secrets-aws-spring-boot-autoconfigure`; `SecretsConfigTreeEnvironmentPostProcessor` están en `axiz-secrets-file-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-secrets-spring-boot-autoconfigure verify`.

## Métricas de caché

Con un `MeterRegistry`, el resolvedor publica `axiz.secrets.cache.requests` (`result=hit|miss`)
y `axiz.secrets.cache.loads` (`outcome=found|absent|failed`). La única otra etiqueta es la acotada
`resolver=sync|async`; los identificadores y valores de secretos nunca son etiquetas. Una búsqueda
asíncrona coalescida cuenta una sola carga real del backend. No se requiere registro ni exportador para
usar la caché.
