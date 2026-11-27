# axiz-security-redis-spring-boot-autoconfigure

Integración con Redis para el límite de tasa a nivel de clúster.

Este módulo es dueño de la API `pe.axiz.spring.security.redis`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-security-redis-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizSecurityRedisAutoConfiguration`
- `AxizSecurityRedisProperties`

## Configuración

Prefijos de propiedades propios: `axiz.security.rate-limit.redis.*`.

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

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-security-redis-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
