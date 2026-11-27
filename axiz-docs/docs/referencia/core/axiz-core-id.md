<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-id

UUIDv7 y ULID ordenados por tiempo, identificadores de correlación y de idempotencia.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-id</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `CorrelationIds` | Identificadores de correlación y de petición: generación y validación de los valores entrantes. |
| `IdGenerator` | Genera identificadores únicos como texto. |
| `IdempotencyKeys` | Claves de idempotencia: validación de las claves del cliente y derivación determinista a partir de partes. |
| `Ulid` | Identificador único universal ordenable lexicográficamente: marca de tiempo de 48 bits más 80 bits aleatorios, codificado en 26 caracteres base32 de Crockford. |
| `UuidV7` | UUID versión 7 ordenado por tiempo (RFC 9562). |

## Pruebas

UUIDv7 y ULID reservan su estado monotónico con operaciones atómicas compare-and-set. Los
llamadores concurrentes no toman un lock del generador; un retroceso del reloj conserva el orden.
El desborde del contador de UUIDv7 toma prestado el milisegundo siguiente. Se rechazan las marcas de
tiempo fuera del rango de 48 bits sin signo. Los componentes aleatorios provienen de `SecureRandom`;
el proveedor del JDK controla su fuente de entropía. Los identificadores son identidades de
recursos, no credenciales de autorización, como especifica
[RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.html#section-8).

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-id verify`.
