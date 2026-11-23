# axiz-tokenization-vault-redis-spring-boot-starter

Starter sin código para seleccionar Redis/Valkey como presupuesto compartido de la bóveda.
Requiere límites explícitos, credenciales y alarma de conciliación; no configura caché ni el
rate limiter general de la aplicación. Consultar
[configuración y pruebas](../axiz-tokenization-vault-redis-spring-boot-autoconfigure/README.md).

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-tokenization-vault-redis-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-tokenization-vault-redis-spring-boot-autoconfigure`](../axiz-tokenization-vault-redis-spring-boot-autoconfigure/README.md).

## Uso desde el catálogo

```bash
axiz add tokenization-vault-redis
```

Capacidades requeridas: `tokenization-vault`, `secrets`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.tokenization.vault.redis.fail-open: false
axiz.tokenization.vault.redis.connection.tls: true
```

## Operación

Se configura un endpoint Redis/Valkey autenticado con TLS y timeouts acotados de conexión y de
comando. La capacidad y el desalojo se dimensionan según la capacidad: el estado de coordinación o
de idempotencia no puede usar la semántica de desalojo de una caché. Los sustitutos en memoria se
limitan a ambientes local, test o ci declarados explícitamente.

La [guía de tokenización](../../../axiz-docs/docs/guias/tokenizacion.md) describe los límites entre
cliente y bóveda, los perfiles, la autorización, la retención y el flujo independiente de protección
de campos.
