# axiz-security-redis-spring-boot-starter

Límite de tasa a nivel de clúster sobre Redis.

Este módulo es dueño de la API `pe.axiz.spring.security.redis.starter`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-security-redis-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-security-redis-spring-boot-autoconfigure`](../axiz-security-redis-spring-boot-autoconfigure/README.md).

## Uso desde el catálogo

```bash
axiz add security-redis
```

Capacidades requeridas: `security`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.security.rate-limit.enabled: true
axiz.security.rate-limit.store: redis
axiz.security.rate-limit.rules[0].name: api
axiz.security.rate-limit.rules[0].paths:
- /**
axiz.security.rate-limit.rules[0].limit: 100
axiz.security.rate-limit.rules[0].window: 1m
axiz.security.rate-limit.rules[0].fail-open: false
```

## Operación

Se configura un endpoint Redis/Valkey autenticado con TLS y timeouts acotados de conexión y de
comando. La capacidad y el desalojo se dimensionan según la capacidad: el estado de coordinación o
de idempotencia no puede usar la semántica de desalojo de una caché. Los sustitutos en memoria se
limitan a ambientes local, test o ci declarados explícitamente.
