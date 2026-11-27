<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-tokenization-vault-redis-spring-boot-autoconfigure

Compone los presupuestos distribuidos de la bóveda con una conexión Redis/Valkey exclusiva.
El módulo base de tokenización permanece independiente del proveedor. No depende del cliente
Redis de repositorios, caché o rate limiting general y no altera sus opciones.

## Configuración

```yaml
axiz:
  tokenization:
    vault:
      redis:
        namespace: vault-production
        fail-open: false
        connection:
          host: ${TOKEN_BUDGET_HOST}
          port: 6379
          tls: true
          username: vault
          password-secret: token-budget/password
          timeout: 500ms
          queue-capacity: 1000
        policy:
          # Ventanas ilustrativas: revisar los doce grupos para cada despliegue.
          query:
            subject: {burst: 2, minute: 20, day: 100}
            client: {burst: 10, minute: 100, day: 1000}
            tenant: {burst: 20, minute: 200, day: 2000}
          reveal:
            subject: {burst: 1, minute: 5, day: 20}
            client: {burst: 5, minute: 20, day: 100}
            tenant: {burst: 10, minute: 100, day: 500}
          metadata:
            subject: {burst: 2, minute: 20, day: 100}
            client: {burst: 10, minute: 100, day: 1000}
            tenant: {burst: 20, minute: 200, day: 2000}
          writes:
            subject: {burst: 1, minute: 10, day: 50}
            client: {burst: 5, minute: 50, day: 500}
            tenant: {burst: 10, minute: 100, day: 1000}
          subject-concurrent: ${TOKEN_SUBJECT_CONCURRENT}
          client-concurrent: ${TOKEN_CLIENT_CONCURRENT}
          tenant-concurrent: ${TOKEN_TENANT_CONCURRENT}
          concurrency-retry-after: 1s
```

Los cuatro grupos de política son mapas con tres dimensiones `subject`, `client` y `tenant`;
cada una contiene `burst`, `minute` y `day`. Se pueden suministrar sus propiedades completas
desde Config Data.

Estos números solo ilustran el formato. Definir los doce grupos de ventanas según el uso y
riesgo del despliegue; no existen cuotas productivas universales. `fail-open=true`, límites
ausentes/no positivos, namespace inválido, colas ilimitadas o plazos excesivos impiden arrancar.
Todas las réplicas comparten namespace, política e infraestructura. La semántica de ventanas,
reservas y conciliación está en el [adaptador](../adapters/axiz-adapter-tokenization-redis.md).

Se requiere un `SecretResolver` y un `TokenBudgetLeaseAlarm`. El secreto de autenticación se
carga en el arranque, se conserva en un contenedor cerrable y no se resuelve desde los hilos
Netty al reconectar. La renovación de esta credencial requiere recrear el contexto/proceso;
el despliegue debe permitir una transición controlada de credenciales. El cierre intenta borrar
la copia propia; no garantiza borrar copias internas del driver o de la JVM. Nunca usar el PAN
ni otro dato del negocio como credencial, namespace, host o dimensión de configuración.

TLS usa validación de cadena y hostname (`FULL`) con el truststore de la JVM. Solo local/test/ci
pueden usar `tls=false`, además de `axiz.transport.allow-insecure-data-stores=true`. Se exige
autenticación incluso en esos entornos; no se admite una URL con contraseña incrustada.

## Propiedad, fallos y operación

La integración posee cliente, recursos y una conexión. Publica únicamente `TokenBudget` en
servlet/no-web o `AsyncTokenBudget` nativo en WebFlux. No exporta la conexión como bean que
otro componente pueda reutilizar. Tiene dos hilos de I/O y dos de cómputo para el transporte;
no usa un executor para envolver comandos síncronos en la ruta reactiva. La cola nativa del
adaptador serializa los comandos para evitar contención del driver entre event loops.

El arranque conecta, autentica y comprueba `PING` con plazos finitos; no crea claves de cuotas.
Esto demuestra acceso al transporte, no todos los permisos de scripts ni durabilidad del
servidor. Redis debe tener ACL para los comandos del adaptador, `noeviction`, persistencia,
monitorización y un procedimiento de recuperación probado. No se ofrece soporte de Cluster,
Sentinel o garantía de conservación de cuotas tras pérdida de estado en esta entrega.

Fallar la conexión o autenticación impide arrancar y cierra los recursos adquiridos. No se
sustituye el presupuesto por memoria local. El apagado cierra la conexión mediante su cliente,
los recursos propios y la copia de credenciales; no cierra clientes de otras capacidades.

## Pruebas

`TokenRedisBootstrapTest` valida TLS previo a la resolución de secretos, límites y propiedad
de credenciales. `ValkeyTokenBudgetSpringIT` arranca Valkey autenticado y comprueba dos contextos
con cuotas compartidas/cierre independiente, rechazo por contraseña incorrecta y 64 admisiones
nativas concurrentes instrumentadas con BlockHound. Desde `axiz-framework`:

```sh
mvn -Pquality -pl quality/axiz-store-integration-tests -am \
  -Dtest=TokenRedisBootstrapTest,ValkeyTokenBudgetSpringIT \
  -Dsurefire.failIfNoSpecifiedTests=false -Daxiz.it.requireDocker=true test
```
