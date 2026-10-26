# Caché, locks y secuencias

## Caché

```java
@AxizCacheable(value = "rates", ttl = "5m")
public ExchangeRate rate(String currency) { ... }
```

- Dos niveles: L1 local (acotado, TTL corto para que las réplicas converjan) y L2 Redis
  (ElastiCache/Valkey) cuando hay conexión.
- Llaves `<servicio>:<caché>:k4:<método>:<alcance>:<argumentos>`: la firma del método (clase,
  nombre y tipos de parámetros) separa sobrecargas y métodos distintos, y cada argumento lleva su tipo (`"7"` y
  `7`, `null` y `"-"` nunca comparten llave). Dos métodos comparten entradas solo si lo declaran
  con `keyName`. Se aceptan textos, números, booleanos, enums, UUID, valores de `java.time`,
  `Currency`, `Locale`, arreglos, colecciones, mapas, `Optional`, records y tipos que implementan
  `CacheKeyPart`; cualquier otro objeto se rechaza, porque su `toString()` no es una identidad
  estable. Arreglos y colecciones llevan su clase en la llave: `int[]{7}`, `Integer[]{7}`,
  `List.of(7)` y un `ArrayDeque` con 7 son llaves distintas, igual que lo serían los argumentos.
  `k4` es la versión del formato: un cambio de formato cambia todas las llaves y las entradas
  viejas expiran solas.
- **Alcance** (`scope`): quién comparte las entradas además de los argumentos.
    - `TENANT` (por defecto): solo las llamadas del mismo tenant (el del contexto, el de
      `TenantContext` en servlet o el del contexto de Reactor en reactivo; ninguno en un servicio
      de un solo tenant). Dos tenants nunca leen sus entradas aunque el método reciba un id local
      al tenant (un código de cliente, un número de página).
    - `CALLER`: además, solo el mismo usuario autenticado (el nombre del principal de Spring
      Security), para resultados que dependen de quién pregunta. Sin usuario autenticado el
      método corre sin caché.
    - `GLOBAL`: todos comparten, solo para datos que son iguales para todos (un catálogo, la lista
      de bancos).

    ```java
    @AxizCacheable(value = "my-limits", ttl = "1m", scope = CacheScope.CALLER)
    public Limits myLimits() { ... }
    ```

    Un acierto de caché no ejecuta el método: la autorización va antes de la llamada, no adentro.
- Valores serializados en JSON y **single flight**: un valor que falta se carga una sola vez
  aunque lo pidan muchos hilos.
- Una copia que L1 toma de L2 nunca vive más que el original: su TTL es el menor entre lo que le
  queda en Redis (`PTTL`, en el mismo script que la lectura), descontado el tiempo que tardó la
  lectura (medido con reloj monotónico), y el límite de L1. Si la respuesta llegó cuando el original
  ya vencía, no se copia. Una entrada sin expiración en Redis se copia por el límite de L1; si el
  nivel remoto no puede decir cuándo vence (un `ByteCache` propio sin `getWithTtl`), el valor se
  devuelve pero no se copia a L1.
- En reactivo funciona sobre `Mono` y `Flux`. Si Redis no responde, la caché se degrada (se llama a
  la fuente) en lugar de fallar.
- Programático con `AxizCache.getOrLoad(key, type, loader)` y `evict(key)`; TTL por caché en
  `axiz.cache.caches.<nombre>.ttl`.
- La invalidación por patrón usa `SCAN`, nunca `KEYS`.

## Locks distribuidos

```java
Movement m = lockTemplate.withLock("account:" + accountId,
    Duration.ofSeconds(10),    // lease: un dueño caído nunca bloquea más que esto
    Duration.ofSeconds(2),     // espera máxima para tomarlo
    lease -> {
      lease.ensureHeld();      // justo antes del efecto
      return core.debit(order, key);   // idempotente en el core: la llave rechaza un segundo débito
    });
```

`LockTemplate` renueva la concesión en segundo plano mientras el trabajo corre y siempre libera.
Redis (`SET NX PX` + scripts que solo actúan con el token del dueño) o DynamoDB (escrituras
condicionales): `axiz.lock.store`.

Si la concesión se pierde mientras el trabajo corre (el almacén rechaza la renovación, o no se
pudo renovar antes de que venciera), otra réplica puede tomar el recurso. Por eso:

- el hilo del trabajo se interrumpe en ese momento (cancelación cooperativa: una llamada bloqueante
  termina con `InterruptedException`);
- `withLock` nunca devuelve el resultado: termina con `LockLostException` (`AXZ-LOCK-0503`, 503,
  se puede reintentar), y si el trabajo falló por su cuenta, su excepción lleva la pérdida como
  suprimida;
- el trabajo que recibe `lease` llama `lease.ensureHeld()` justo antes de cada efecto y puede usar
  `lease.token()` en escrituras condicionales.

Una excepción al final no deshace lo que el trabajo ya hizo. Para efectos de dinero, el recurso
mismo debe rechazar a un dueño anterior: una llave de idempotencia persistida, una transacción o
una escritura condicional con el token. El lock evita el trabajo duplicado en el caso normal; esas
garantías lo evitan cuando una réplica se pausa o pierde la red.

## Tareas programadas en una sola réplica

`axiz add scheduling` trae ShedLock (JDBC o DynamoDB):

```java
@Scheduled(cron = "0 0 2 * * *", zone = "America/Lima")
@SchedulerLock(name = "purge-expired", lockAtMostFor = "10m")
public void purge() { ... }
```

## Secuencias

Números de operación legibles, por ejemplo `OP-20261011-00000123`, con reinicio diario, mensual o
anual y reserva por bloques (`block-size`):

```yaml
axiz:
  sequence:
    store: jdbc        # o dynamodb
    sequences:
      operation:
        prefix: OP
        width: 8
        reset: DAILY          # NEVER | DAILY | MONTHLY | YEARLY
        zone: America/Lima
```

```java
String number = sequences.next("operation");   // o sequences.generator("operation").next()
```

Las secuencias toleran huecos (un bloque reservado y no usado se pierde al reiniciar); no sirven
para numeraciones legales sin huecos.
