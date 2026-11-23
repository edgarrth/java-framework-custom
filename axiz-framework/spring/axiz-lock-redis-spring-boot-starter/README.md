# axiz-lock-redis-spring-boot-starter

Locks con Redis.

Este módulo es dueño de la API `pe.axiz.spring.lock.redis.starter`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-lock-redis-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-lock-redis-spring-boot-autoconfigure`](../axiz-lock-redis-spring-boot-autoconfigure/README.md).

## Operación

Se configura un endpoint Redis/Valkey autenticado con TLS y timeouts acotados de conexión y de
comando. La capacidad y el desalojo se dimensionan según la capacidad: el estado de coordinación o
de idempotencia no puede usar la semántica de desalojo de una caché. Los sustitutos en memoria se
limitan a ambientes local, test o ci declarados explícitamente.
