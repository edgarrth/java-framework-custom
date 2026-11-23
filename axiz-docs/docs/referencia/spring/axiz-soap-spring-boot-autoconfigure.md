<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-soap-spring-boot-autoconfigure

Clientes SOAP con parseo XML seguro: timeouts, WS-Security, mapeo de faults, logs enmascarados y observaciones (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-soap-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizSoapAutoConfiguration` | Clientes SOAP con parseo XML seguro, WS-Security, mapeo de faults, enmascaramiento y observaciones. |
| `AxizSoapProperties` | Clientes SOAP para sistemas externos (`axiz.soap.clients.<name>`). |
| `SoapClients` | Clientes SOAP con nombre. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-soap-spring-boot-autoconfigure verify`.

## Seguridad del transporte

Todos los endpoints SOAP se validan al arranque, incluidos los clientes sin credenciales WS-Security. Se
exige HTTPS. Los pares HTTP locales necesitan la configuración común `axiz.transport.allow-plain-http=true`
y un entorno explícito local/test/ci. No existe un selector de texto plano por cliente. Los transportes
SOAP gestionados no siguen redirecciones automáticas y conservan la verificación normal de certificados.

## Credenciales, TLS mutuo y rotación

```yaml
axiz.soap.clients.bank:
  endpoint: https://bank.example.invalid/soap
  connect-timeout: 2s
  timeout: 10s
  username: integration-client
  password-ref: bank/soap/password
  password-mode: TEXT
  ssl-bundle: bank-client
```

Se declara el SSL bundle de Spring `bank-client` con sus certificados de confianza y, para TLS mutuo, el
certificado y la clave privada del cliente. El material de claves se guarda fuera del artefacto de la
aplicación y se usa el mecanismo de secretos del despliegue. La confianza, los protocolos habilitados y los
cifrados se aíslan por cliente con nombre; la verificación del hostname permanece habilitada. Un bundle
desconocido hace fallar la creación del cliente.

WS-Security resuelve `password-ref` en cada llamada, cierra el `SecretValue` devuelto y limpia el buffer
temporal de credenciales después de la petición, incluso ante fallos. La vigencia sigue dependiendo de la
caché o el TTL del resolvedor de secretos seleccionado: esa caché se rota o invalida según haga falta.
El transporte y su contexto SSL se reutilizan. La rotación de certificados o de confianza requiere
reemplazar el bean `SoapClients` (o un reinicio gradual de la aplicación); no se ofrece recarga automática
de SSL bundles. Se drenan las peticiones, se crea el reemplazo con el nuevo bundle y se cierra la fábrica
anterior. El cierre libera los clientes HTTP propios y rechaza las llamadas posteriores.

Los consumidores programáticos pueden usar `AxizSoapProperties.Client.builder(endpoint).sslBundle("bank-client")`
y las opciones con nombre antes de `build()`. El resultado es inmutable; los valores por defecto son SOAP
1.1, un timeout de conexión de 2 segundos y un timeout de petición de 10 segundos. `LEGACY_SHA1_DIGEST` se
selecciona solo cuando la contraparte exige explícitamente ese perfil WS-Security basado en SHA-1, siempre
sobre TLS.

`SoapTest` cubre la renovación y limpieza de secretos, la configuración inválida y los valores por defecto
del builder; `SoapTlsTest` comprueba TLS y mTLS aislados con un par TLS real. Las recetas de proveedores de
la demo ilustran el consumo mediante el starter público.

Las combinaciones de credenciales configuradas (usuario, modo de contraseña explícito, referencia de
secreto y disponibilidad de SecretResolver) se validan al construir la fábrica. Esto no obtiene secretos
ni crea conexiones de red. La existencia del secreto y su valor actual se comprueban en cada invocación,
de modo que la rotación sigue siendo efectiva sin guardar texto plano en caché en la fábrica SOAP.
