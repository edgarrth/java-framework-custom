<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-tokenization-dynamodb

Bóveda de tokens atómica sobre DynamoDB, con construcción compartida de peticiones e I/O asíncrona nativa.

Este módulo es dueño de la API `pe.axiz.adapter.tokenization.dynamodb`.

El operador aprovisiona una tabla con una clave de partición de tipo cadena llamada `pk` y sin clave
de ordenamiento. Este adaptador nunca crea la tabla ni activa el TTL automático. La versión 1 del
esquema almacena:

| Prefijo | Contenido |
| --- | --- |
| `T#` | Token, ámbito enmarcado, perfil y versión canónicos, keyset, lista de huellas, revisión, bytes del sobre, marcas de tiempo y metadatos acotados |
| `F#` | Referencia de ámbito, clave y HMAC, token asociado, keyset e identificador de clave |
| `C#` | Control versionado del keyset: clave de escritura y claves de lectura ordenadas |
| `E#` | Control versionado e independiente de la clave de envoltura de sobres |
| `R#` | Reserva permanente de cada identificador de clave HMAC o de envoltura activado |
| `I#` | Marcador permanente de inicialización de cada control HMAC o de envoltura |

`DynamoDbTokenVault` y `DynamoDbAsyncTokenVault` toman prestados sus clientes. Cada petición tiene un
timeout explícito entre 1 ms y 30 segundos. Las consultas usan instantáneas transaccionales de las
referencias y de los registros propietarios, de modo que un borrado concurrente se distingue de un
huérfano persistente. Las escrituras comprueban la versión del control HMAC y la ruta de escritura del
sobre; los borrados también comprueban el texto cifrado para proteger frente a una encarnación recreada
del token. Las DEK enrutadas son obligatorias en este adaptador de bóveda. No hay alternativa cuando
falta un ítem de control o cuando una DEK en caché pertenece a una ruta de escritura anterior. Los
conflictos de transacción tienen un presupuesto de reintentos acotado. Un timeout, un fallo de
transporte o un error ambiguo del servicio es una escritura incierta, no una colisión confirmada.

`DynamoDbVaultMaintenance` es el adaptador privilegiado separado que usa `VaultRotator`. Añade la
inicialización condicional de controles, transiciones de control con compare-and-set, recorridos
consistentes acotados y la migración atómica de registros y referencias. Los adaptadores operativos
no exponen ninguno de esos métodos. El cliente de mantenimiento inyectado debe tener su propio rol IAM;
construir una clase Java distinta no establece por sí mismo un límite IAM. Los servicios ordinarios no
deben escribir ítems de control ni recorrer la tabla. No se concede `dynamodb:*`; el acceso
transaccional se autoriza mediante acciones sobre ítems.

Los controles HMAC y de envoltura se inicializan mediante el protocolo privilegiado. Sus identificadores
de clave se reservan de forma atómica y permanente. Retirar una clave del conjunto de lectura no permite
reutilizar después su identificador; los ítems de reserva, los marcadores de inicialización y las
versiones de control se conservan durante el respaldo y la restauración. Si se pierde un ítem de
control, la inicialización no puede restablecerlo con otra clave: se restaura y se concilia su estado
previo antes de atender escrituras.
El recifrado comprueba la ruta de escritura vigente del sobre. Una migración solo de índices conserva
el texto cifrado y, en su lugar, comprueba que su ruta siga siendo una lectora permitida. Ambas
variantes también protegen la encarnación exacta del registro, de modo que un job retrasado no puede
volver a poner un sobre anterior después de que otro job lo reemplazó.

El esquema exacto de almacenamiento es estricto. Las filas mal formadas, los ámbitos que no coinciden,
las referencias propias ausentes y los propietarios en conflicto fallan en modo cerrado. El recorrido de
referencias bloquea el retiro incluso cuando falta la fila del token referenciado. El TTL automático no
puede borrar de forma atómica un token y sus índices; se usa la eliminación coordinada del protocolo.

`VaultRetention` proporciona el job de mantenimiento separado y auditado para la expiración lógica. Usa
condiciones de revisión, marcas de tiempo, texto cifrado y propiedad de referencias, sin descifrar
valores. El [paquete de infraestructura](https://github.com/edgarrth/java-framework-custom/blob/main/axiz-framework/adapters/axiz-adapter-tokenization-dynamodb/infra/README.md) incluye la plantilla CloudFormation del
esquema, las políticas separadas de operación, migración y retención, y el procedimiento de simulación
de AWS IAM.

AWS documenta la [consistencia y paginación de los recorridos](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Scan.html)
y el [aislamiento e idempotencia de las transacciones](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html).
El recorrido administrativo no es una instantánea. El protocolo del núcleo se apoya en la eliminación
monótona de referencias y en escrituras condicionales bajo la misma versión de control. La prueba de
integración local no valida tablas globales ni escritores arbitrarios directos o de importación.

Desde `axiz-framework`, la suite de integración local se ejecuta con Docker disponible:

```sh
mvn -Pquality -pl quality/axiz-store-integration-tests -am \
  -Dtest=LocalStackTokenVaultIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Daxiz.it.requireDocker=true test
```

LocalStack ejercita peticiones y transacciones reales del SDK. No certifica AWS IAM, la operación de
KMS, la durabilidad en producción, el cumplimiento PCI ni las integraciones independientes de auditoría
y de límite de tasa.

## Errores del proveedor y escrituras inciertas

Un fallo del cliente o del transporte del SDK no demuestra que DynamoDB no recibió una mutación.
`SdkClientException`, los acuses perdidos y los errores genéricos del proveedor después del envío
quedan como `VaultUncertainException`; el motor registra una conciliación restringida y no los
reintenta como colisión de token. Solo los motivos confirmados de cancelación condicional de la
transacción se clasifican como colisión o contención, y la validación local previa al envío no pudo
haber escrito. Los errores del cliente no se traducen de forma general a un resultado no aplicado.
`DynamoDbVaultTest` ejercita acuses perdidos con ambos clientes y verifica exactamente un envío de
transacción; la suite de la bóveda del núcleo comprueba el comportamiento de conciliación y
auditoría. Estas pruebas no pretenden reproducir una caída real de AWS.
