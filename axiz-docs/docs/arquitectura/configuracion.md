# Configuración y secretos

Los parámetros de cada servicio se cargan desde **ConfigMaps** que el chart monta como archivos en
el pod; los **secretos** nunca van en un ConfigMap y vienen de **AWS Secrets Manager**. El servicio
no consulta la API de Kubernetes: el pod no monta token de ServiceAccount y no existe Role ni
RoleBinding, así un pod comprometido no puede listar los ConfigMaps del namespace.

## Precedencia

De menor a mayor:

1. `application.yaml` dentro del JAR: solo valores por defecto seguros, nunca valores de un ambiente.
2. Parameter Store (`optional:aws-parameterstore:/axiz/<ambiente>/<servicio>/`), si
   `axiz.config.parameter-store.enabled=true`.
3. ConfigMap compartido `axiz-common` del namespace: endpoints de observabilidad, issuers permitidos,
   región, brokers, calendario de feriados, niveles de log, timeouts por defecto.
4. ConfigMap del servicio (nombre = `spring.application.name`) y `<servicio>-errors` (catálogo de
   errores).
5. Variables de entorno del Deployment, solo para sobrescrituras puntuales.
6. Secretos montados como archivos.

El starter `axiz-config` aplica la convención: dentro del clúster (y fuera de los perfiles `local` y
`test`) importa los archivos montados bajo `axiz.config.directory` (por defecto `/etc/axiz/config`):
`common/application.yaml` (`axiz-common`, opcional), `service/application.yaml` y
`errors/application.yaml` (obligatorios: si el ConfigMap falta, el servicio no arranca). Los
posteriores ganan sobre los anteriores. Fuera del clúster no importa nada. El cliente de
Parameter Store de Spring Cloud AWS sigue a `axiz.config.parameter-store.enabled` (falso por
defecto), así un servicio que no lo usa no crea un `SsmClient` ni necesita una región de AWS para
arrancar. Lo que el servicio declare explícitamente siempre gana.

## Reglas

- Un namespace por ambiente: los ConfigMaps se llaman igual en dev, qa y prod; cambia su contenido,
  no su nombre.
- Cada ConfigMap lleva una llave `application.yaml` con propiedades tipadas; toda propiedad se valida
  con `@ConfigurationProperties` + `@Validated` y el servicio no arranca si es inválida.
  `axiz.config.required` lista llaves que deben existir.
- Recarga desactivada: un cambio de ConfigMap dispara un rollout (el chart anota el checksum del
  ConfigMap en el pod).
- El chart crea un ServiceAccount sin token montado (`automountServiceAccountToken: false`) y sin
  permisos sobre la API de Kubernetes; la identidad de AWS (IRSA o EKS Pod Identity) usa su propio
  token proyectado. Un servicio que necesite la API por otro motivo lo declara con
  `serviceAccount.automountToken: true` y su propio Role.

En el chart, `values-<env>.yaml` define el contenido:

```yaml
config:
  axiz.config.environment: qa
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
