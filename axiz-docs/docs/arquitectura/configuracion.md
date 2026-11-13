# Configuración y secretos

Los parámetros de cada servicio se cargan desde **ConfigMaps** que el chart monta como archivos en
el pod; los **secretos** nunca van en un ConfigMap y vienen de **AWS Secrets Manager**. El servicio
no consulta la API de Kubernetes: el pod no monta token de ServiceAccount y no existe Role ni
RoleBinding, así un pod comprometido no puede listar los ConfigMaps del namespace.

## Precedencia

De menor a mayor:

1. `application.yaml` dentro del JAR: solo valores por defecto seguros, nunca valores de un ambiente.
2. Parameter Store mediante el resolver propio de Axiz, si
   `axiz.config.parameter-store.enabled=true`.
3. ConfigMap compartido versionado e inmutable, seleccionado con `sharedConfigMap`: endpoints de observabilidad, issuers permitidos,
   región, brokers, calendario de feriados, niveles de log, timeouts por defecto.
4. ConfigMap del servicio (nombre = `spring.application.name`) y `<servicio>-errors` (catálogo de
   errores).
5. Variables de entorno del Deployment, solo para sobrescrituras puntuales.
6. Secretos montados como archivos.

El starter `axiz-config` importa, dentro del clúster y fuera de los entornos operativos explícitos
`local`, `test` y `ci`, los archivos montados bajo `axiz.config.directory` (por defecto
`/etc/axiz/config`): `common/application.yaml` cuando se selecciona configuración compartida,
`service/application.yaml` y `errors/application.yaml`. Los posteriores prevalecen sobre los
anteriores. Los imports son `file:`; no se consulta la API de Kubernetes.

El bootstrap de Parameter Store pertenece a `AxizParameterStoreResolver` y `AxizParameterStoreLoader`.
Está desactivado por defecto. Si se habilita, el entorno y el identificador de aplicación deben
estar disponibles antes de ConfigData: `AXIZ_CONFIG_ENVIRONMENT` y
`AXIZ_CONFIG_PARAMETER_STORE_APPLICATION`. El chart los exporta como variables del proceso junto
con `SPRING_APPLICATION_NAME`; activar un perfil no sustituye estos selectores. No hay namespace
implícito `default`. Los archivos o parámetros remotos no pueden cambiar los selectores capturados.

## Reglas

- Un namespace por ambiente. Los ConfigMaps del servicio tienen nombres estables dentro de él;
  el ConfigMap compartido es opcional y tiene nombre versionado e `immutable: true`.
- Cada ConfigMap lleva una llave `application.yaml` con propiedades tipadas; toda propiedad se valida
  con `@ConfigurationProperties` + `@Validated` y el servicio no arranca si es inválida.
  `axiz.config.required` lista llaves que deben existir.
- Recarga desactivada. El checksum de los ConfigMaps que renderiza el chart provoca un rollout
  al cambiar su contenido. Un ConfigMap compartido externo no se consulta con `lookup` ni tiene
  checksum de contenido en este chart: se crea otra versión inmutable y se cambia `sharedConfigMap`.
  El cambio del nombre en el pod provoca el rollout. Si no se selecciona, no se monta; si se
  selecciona un nombre ausente, Kubernetes impide arrancar el pod.
- El chart crea un ServiceAccount sin token montado (`automountServiceAccountToken: false`) y sin
  permisos sobre la API de Kubernetes; la identidad de AWS (IRSA o EKS Pod Identity) usa su propio
  token proyectado. Un servicio que necesite la API por otro motivo lo declara con
  `serviceAccount.automountToken: true` y su propio Role.

En el chart, `values-<env>.yaml` define el contenido:

```yaml
environment: qa
sharedConfigMap: axiz-common-2-0-0
configBootstrap:
  parameterStore:
    enabled: false
    application: payments
    prefix: /axiz
config:
  axiz.http-client.clients.core-banking.base-url: https://core-banking.qa.internal
errors:
  - code: PAYMENTS-BUSINESS-0001
    category: UNPROCESSABLE
    title: Daily limit exceeded
```

## Secretos

AWS Secrets Manager es la fuente única. El **External Secrets Operator** sincroniza cada secreto
a un Secret de Kubernetes que el pod monta como archivos en `/var/run/secrets/axiz`; el servicio
los lee con `SecretResolver` o los referencia desde propiedades con `*-ref`:

```yaml
axiz:
  http-client:
    clients:
      core-banking:
        auth:
          type: CLIENT_CREDENTIALS
          client-secret-ref: core-banking-client-secret   # nombre del secreto, no su valor
```

```java
SecretValue apiKey = secretResolver.require("partner-api-key");
```

El chart declara qué llaves trae el ExternalSecret (`externalSecret.keys`) y su prefijo remoto por
ambiente (`<ambiente>/<servicio>`). `SecretValue` nunca se imprime en logs (`toString` enmascarado)
y `CachingSecretResolver` relee periódicamente para tomar rotaciones.

Las credenciales de AWS llegan por **IRSA** o **EKS Pod Identity** (anotación del ServiceAccount
por ambiente), nunca por llaves. Parameter Store es complementario: parámetros que comparten
servicios fuera del clúster o que administra otro equipo; sus `SecureString` no reemplazan a
Secrets Manager para credenciales.

En local, `local/dev-secrets.sh` crea los mismos nombres con valores aleatorios en `local/secrets`.
