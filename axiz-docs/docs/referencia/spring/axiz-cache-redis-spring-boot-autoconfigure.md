<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-cache-redis-spring-boot-autoconfigure

Integración de caché con Redis.

Este módulo es dueño de la API `pe.axiz.spring.cache.redis`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-cache-redis-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizCacheRedisAutoConfiguration`
- `AxizCacheRedisProperties`

## Configuración

Prefijos de propiedades propios: `axiz.cache.*`.

## Operación

Se configura un endpoint Redis/Valkey autenticado con TLS y timeouts acotados de conexión y de
comando. La capacidad y el desalojo se dimensionan según la capacidad: el estado de coordinación o
de idempotencia no puede usar la semántica de desalojo de una caché. Los sustitutos en memoria se
limitan a ambientes local, test o ci declarados explícitamente.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-cache-redis-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
