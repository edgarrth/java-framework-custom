# Tokenization consumer starter

Punto de entrada solo de dependencias para el consumidor remoto. `axiz.tokenization.mode` se configura
explícitamente y se seleccionan la capacidad de cliente HTTP y la pila servlet o reactiva de la aplicación.
La [guía de autoconfiguración](../axiz-tokenization-spring-boot-autoconfigure/README.md) describe la autenticación,
los timeouts, el ciclo de vida, la incertidumbre y el uso. Este starter no selecciona servidor, base de datos ni claves.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-tokenization-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-tokenization-spring-boot-autoconfigure`](../axiz-tokenization-spring-boot-autoconfigure/README.md).

## Uso desde el catálogo

```bash
axiz add tokenization
```

Capacidades requeridas: `http-client`, `security`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.tokenization.mode: remote
axiz.tokenization.remote.client: token-vault
axiz.http-client.clients.token-vault.base-url: ${TOKEN_VAULT_URL}
axiz.http-client.clients.token-vault.auth.type: TOKEN_RELAY
axiz.http-client.clients.token-vault.retry: NONE
axiz.http-client.clients.token-vault.max-attempts: 1
axiz.http-client.clients.token-vault.max-concurrent-calls: 32
axiz.http-client.clients.token-vault.max-connections: 32
axiz.http-client.clients.token-vault.connect-timeout: 2s
axiz.http-client.clients.token-vault.read-timeout: 5s
axiz.http-client.clients.token-vault.total-timeout: 10s
```

La [guía de tokenización](../../../axiz-docs/docs/guias/tokenizacion.md) describe los límites entre
cliente y bóveda, los perfiles, la autorización, la retención y el flujo independiente de protección
de campos.
