<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-kit-dynamodb

Repositorio genérico de DynamoDB sobre el Enhanced Client, con paginación por cursor y convertidores.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-kit-dynamodb</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `CursorCodec` | Cursor de paginación autenticado y ligado a la consulta, con rotación por identificador de clave y cifrado AES-GCM opcional (claves de tipo cadena, número y binario). |
| `DynamoRepository` | Repositorio genérico sobre el DynamoDB Enhanced Client: lectura por clave de partición (y de ordenamiento), escrituras condicionales y simples, borrados, consultas sobre la tabla, GSI y LSI con paginación por cursor, y transacciones. |
| `DynamoAsyncRepository` | API nativa con CompletionStage, bloqueo optimista del SDK y cancelación de la petición exacta de bajo nivel; las consultas obtienen una página acotada. |
| `EncryptedAttributeConverter` | Convertidor de String inyectado por columna, con un contexto de cifrado explícito. |
| `EncryptedTableSchema` | Construye un esquema del Enhanced Client con convertidores inyectados y contextos de columna únicos. |
| `MoneyAttributeConverter` | Guarda `Money` en su forma de texto (`"PEN 150.00"`), nunca como flotante binario. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl kits/axiz-kit-dynamodb verify`.

## Uso asíncrono

Se entrega un `DynamoDbAsyncClient` propiedad de la aplicación y el esquema se construye al arrancar.
El repositorio no cierra ese cliente. El mapeo y los convertidores de atributos se ejecutan en el hilo
del llamador o en el de finalización del SDK y deben hacer solo trabajo de CPU; un convertidor que
llama a KMS de forma síncrona no es adecuado para esta API. Las consultas conservan el mismo formato
opaco de cursor que el repositorio síncrono. El `table()` devuelto es una vía de escape al SDK para
describir transacciones; las operaciones directas del SDK tienen su propia semántica de cancelación.

## Cifrado de campos inyectado

El esquema se construye en el adaptador de persistencia al arrancar y recibe el `FieldEncryptor`
propiedad de ese contexto de aplicación. `EncryptedAttributeConverter` no tiene constructor sin
argumentos: no se registra mediante `@DynamoDbConvertedBy` ni en un registro global.

```java
TableSchema<Customer> schema =
    new EncryptedTableSchema<>(Customer.class, Customer::new, fieldEncryptor)
        .attribute(StaticAttribute.builder(Customer.class, String.class)
            .name("id").getter(Customer::getId).setter(Customer::setId)
            .tags(StaticAttributeTags.primaryPartitionKey()).build())
        .encryptedString("dni", "customer.dni", Customer::getDni, Customer::setDni)
        .build();
```

El modelo aporta el constructor, los getters y los setters referenciados arriba. El esquema
resultante se pasa a `DynamoRepositories.create(table, schema)` o a su equivalente reactivo; la
sobrecarga que recibe solo la clase usa el descubrimiento por anotaciones del SDK y no puede inyectar
este convertidor. Un proveedor de convertidores seleccionado solo por tipo Java cifraría todos los
String con un único contexto, por eso los atributos cifrados se registran uno por uno. Los nombres y
los contextos de las columnas cifradas no pueden repetirse.

El mapeo usa claves locales y no hace ninguna petición a KMS. La utilidad impide sustituir texto
cifrado entre columnas. Los convertidores de atributos no pueden ligar un texto cifrado al ID de fila
de otro atributo; cuando se requiere esa protección, se usa `FieldProtector` con un binding explícito
de fila antes de construir el ítem de persistencia. Su texto cifrado codificado se guarda sin aplicar
otro convertidor.

`EncryptedTableSchemaTest` ejercita `itemToMap`/`mapToItem` del SDK, la sustitución entre columnas,
proveedores de claves independientes y tipos de atributo mal formados; no requiere un servidor DynamoDB.

## Cursores de paginación autenticados

Se usa `DynamoRepositories.cursors()` (o la fábrica del repositorio reactivo) con la capacidad
`dynamodb-cursor`. Se configuran `axiz.dynamodb.cursor.current-key-id`, `key-ids`, `secret-prefix`
(por defecto `cursor-key-`) y `encrypt` (por defecto `false`). El material de clave se resuelve desde
los secretos una sola vez al arrancar. Las mismas claves se aprovisionan en todas las réplicas; una
clave nueva se despliega en cada réplica antes de hacerla vigente, y la clave anterior se retira solo
cuando la política de vida de sus cursores lo permite.

El formato es `v1.<kid>.<payload>.<mac>`. HMAC autentica la versión, el ID de clave, el payload y el
binding del llamador. El repositorio liga la tabla o el índice, la operación, la partición y el prefijo
de ordenamiento; las consultas personalizadas deben ligar todos sus filtros y su ámbito de
autorización. Una firma no autoriza una consulta: las restricciones de tenant o de cliente se derivan
del llamador autenticado y las claves devueltas se validan. Se configura `encrypt=true` cuando los
atributos de clave del cursor también deben ser confidenciales.

Para consultas personalizadas se usan `codec.encode(lastEvaluatedKey, binding)` y
`codec.decode(cursor, binding)`. Los repositorios construidos sin codec pueden hacer CRUD, pero las
consultas paginadas fallan con un error de configuración que indica cómo corregirlo. No existe un modo
sin firma: los cursores mal formados, alterados, de otra consulta, sin firma o con una clave retirada
producen `INVALID_CURSOR` (BAD_REQUEST/400). Los clientes reinician la paginación en lugar de probar
el cursor de otro tenant.
