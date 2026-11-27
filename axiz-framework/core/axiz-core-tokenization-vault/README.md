# axiz-core-tokenization-vault

Orquestación de la bóveda de tokens con principal explícito y puertos de persistencia atómica.

Este módulo es dueño de la API `pe.axiz.core.tokenization.vault`.

`VaultTokenizer` y `AsyncVaultTokenizer` reciben un `TokenCaller` verificado, un tipo registrado y
un dominio autorizado. Comparten la misma política de acceso, perfil, canonicalización, expiración y
huella. El motor asíncrono usa completion stages nativos. Cancelar un resultado no cancela una
mutación aceptada: se espera su plazo finito en el proveedor, se registra el resultado y se liberan
sus recursos. Toda integración impone esos plazos del proveedor.

Los resultados públicos contienen un token opaco y los metadatos permitidos de forma explícita. El
texto cifrado y el HMAC permanecen dentro de la bóveda. La API independiente `FieldProtector`
pertenece a `axiz-core-crypto`. Ninguna de las dos clases configura un respaldo en memoria, una
identidad anónima ni un destino de auditoría permisivo.

## Mantenimiento privilegiado

`VaultRotator` es un proceso administrativo síncrono. Está separado de los endpoints de consumo y usa
una identidad de almacenamiento autorizada de forma independiente. Su política obligatoria comprueba
la autoridad administrativa, el conjunto de claves y el ámbito de cada registro. Confirma un evento
de auditoría STARTED antes de descifrar o escribir y confirma el resultado antes de informar éxito.
Esos eventos de auditoría no incluyen valores de origen, texto cifrado, HMAC ni tokens completos.

La rotación del HMAC sigue estos pasos:

1. Se instala material de clave nuevo y distinto en todas las réplicas participantes, conservando
   el material de lectura anterior.
2. `activate(caller, expectedState, newKey)` incrementa de forma atómica la versión de control,
   selecciona la nueva clave de escritura y conserva todas las claves de lectura. Una clave
   ausente falla de forma cerrada.
3. Se recorre `reindex` desde un cursor nulo hasta que `nextCursor` sea nulo; las páginas vacías
   pueden tener cursor. El proceso autentica el texto cifrado y los HMAC guardados, agrega la
   huella actual y conserva las referencias anteriores. Una fila modificada se informa aparte y
   requiere volver a recorrerla.
4. `preparePrune` recorre el conjunto de claves completo y exige la huella actual en cada fila
   restante. Su plan queda ligado a esa versión de control exacta.
5. Se recorre `prune` con ese plan. Las transacciones condicionales eliminan solo las referencias
   propias y conservan la huella actual. Los procesos tardíos no pueden reintroducir una huella
   anterior.
6. `retire` prueba que ningún registro depende de la clave anterior y que no queda ningún elemento
   de referencia anterior, incluidas las referencias huérfanas. Luego cambia el estado de control
   de forma condicional. Solo cuando no queda ninguna dependencia del conjunto de claves ni del
   despliegue, el operador puede retirar el material secreto.

Los recorridos de prueba tienen presupuestos explícitos de páginas y de tiempo; agotarlos o
interrumpirlos falla de forma cerrada. Un escaneo no es una instantánea global. La seguridad depende
de claves de registro estables, de una eliminación monotónica y de la barrera de versión en **cada**
creación y migración. Las herramientas de restauración o importación y quienes escriben directamente
en la tabla preservan estas invariantes. Una restauración requiere conciliación antes de atender
tráfico. Los identificadores de clave retirados no se reutilizan para otro material.

`reencrypt` recibe un `EnvelopeEncryptor` de destino separado, autentica el origen y conserva el
token, los índices, los metadatos y la vigencia mientras incrementa la revisión. Durante un
despliegue mixto, el operador mantiene disponible el descifrado de los sobres de origen y de
destino. `KeyringDataKeyProvider` y `KeyringAsyncDataKeyProvider` de core admiten un enrutamiento
explícito hacia un máximo de 16 proveedores de claves de envoltura; el KMS de Spring los compone
desde un anillo opcional de claves con ARN inmutables.

La rotación de la clave de envoltura usa un `WrappingKeyState` separado, independiente de la
versión del HMAC:

1. `initializeWrapping` establece la ruta inicial tras una prueba de generación de clave. Los
   proveedores enrutados se configuran antes de la primera escritura en la bóveda; DynamoDB exige
   ambos controles.
2. Se preparan todos los lectores y luego se llama a `activateWrapping` con un anillo que conserva
   cada lector activo y selecciona el nuevo escritor. La inicialización y la activación prueban el
   nuevo proveedor y confirman un cambio de control durable y condicional. Las cachés de DEK
   existentes no evitan la barrera de escritura.
3. Se recorre `reencrypt` con un origen que admite los sobres anteriores y un destino que escribe
   con la ruta actual. El mismo token, ámbito, vigencia y huellas se conservan en la migración.
4. `retireWrapping` rechaza cualquier registro dependiente. Autentica cada sobre restante y comprueba
   sus HMAC con autorización administrativa y auditoría confirmada, y luego elimina de forma
   condicional la ruta de lectura anterior. Las creaciones tardías y los procesos de recifrado no
   pueden reintroducir esa ruta.

El adaptador DynamoDB reserva los identificadores de forma permanente, lo que impide reactivar un
identificador retirado en una generación posterior. Un compare-and-set fallido informa conflicto;
una confirmación perdida igual requiere conciliación. Ningún proceso desactiva ni elimina una clave
KMS. Quitar su ruta de lectura de la bóveda es solo un requisito previo para retirar el material
externo: el operador también considera los respaldos, las réplicas y otras aplicaciones.

Una confirmación de almacenamiento perdida produce `AXZ-TOKEN-0504` y una conciliación durable
restringida. Esa mutación incierta no se reintenta de forma automática. Si la conciliación falla,
se levanta una alarma técnica y el resultado sigue siendo incierto. Una auditoría fallida después
de una escritura confirmada no se compensa borrando ni revirtiendo la asociación durable. Devuelve
el mismo resultado incierto `AXZ-TOKEN-0504` y registra `COMMITTED_AUDIT_UNCONFIRMED`, con el
identificador de la operación y la referencia restringida del token para reparar la auditoría; no
invita a repetir la escritura. La conciliación de mantenimiento distingue una confirmación de
almacenamiento ausente (`UNCERTAIN`) de un `SUCCESS`/`CONFLICT` confirmado cuya auditoría final no
se confirmó. Esto aplica a la tokenización imperativa y a la asíncrona nativa. Un fallo de
conciliación levanta la alarma obligatoria y conserva la respuesta incierta.

## Integridad de la asociación y limpieza de recursos

La búsqueda y la tokenización con deduplicación descifran el candidato dentro de la bóveda,
verifican cada huella guardada contra el texto plano canónico y el ámbito con su clave registrada, y
recalculan los metadatos del perfil. La detokenización realiza la misma autenticación antes de
exponer el texto plano. También se comprueba un candidato devuelto tras una carrera de unicidad.
Copiar referencias, huellas o metadatos no puede hacer pasar otro valor por el solicitado; los
fallos niegan la divulgación y auditan el fallo de integridad. Los buffers temporales de comparación
en texto plano o codificado se borran.

El sobre autentica el valor y su AAD de token y ámbito. Esto no da protección criptográfica contra
la reversión de una instantánea completa de la base ni autentica cada cabecera del almacenamiento:
la coherencia de creación, expiración y revisión también depende de un IAM de almacenamiento
confiable, de escrituras condicionales, de controles de retención y de la conciliación de las
restauraciones. AEAD o la comprobación de huellas por sí solos no protegen contra una reversión
arbitraria hecha con privilegios sobre el almacenamiento.

Una vez confirmados el resultado de negocio y la auditoría requerida, no poder liberar un permiso en
Redis no reemplaza ese resultado ni emite un segundo resultado de negocio contradictorio. La reserva
se mantiene de forma conservadora y el proveedor levanta su alarma administrativa de conciliación.
Confirmar el éxito de negocio no afirma que la capacidad se liberó. Los motores síncrono y asíncrono
nativo comparten esta regla.

La detección interna de PAN y la autenticación del índice ciego toman prestados arreglos de
caracteres mutables y borran los buffers temporales en lugar de llamar a `SensitiveValue.reveal()`.
`BlindIndex` también acepta un `char[]` propio sin cambiar el formato persistido del índice. El
borrado sigue siendo de mejor esfuerzo en la JVM: el parseo de la petición, un perfil de
canonicalización propio o un `reveal()` explícito pueden crear textos planos inmutables. La JVM y el
host se configuran para proteger los volcados de heap y de core y para no retener cuerpos de
peticiones; esta API no certifica la ausencia de texto plano en memoria de extremo a extremo.
