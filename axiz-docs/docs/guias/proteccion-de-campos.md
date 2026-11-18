# Protección independiente de campos

`axiz-core-crypto` permite cifrar, calcular índices de igualdad y descifrar sin instalar una
bóveda ni crear tablas. La aplicación conserva el ciphertext y el índice en su propia tabla.
`FieldProtector` compone esas operaciones; no genera tokens ni persiste valores.

```java
var policy = FieldProtectionPolicy.dniPe("customer-lookup");
var protection = new FieldProtector(fieldEncryptor, blindIndex, policy);
var scope = new FieldScope(authenticatedTenant, "customers");

try (var original = SecretValue.of("00123456")) {
  ProtectedField stored = protection.protect(original, scope, customerId);
  // Persist both fields atomically, with parameterized queries and application authorization.
  repository.save(customerId, stored.ciphertext().encoded(), stored.index().encoded());
}
try (var sought = SecretValue.of("00123456")) {
  var indexes = protection.candidates(sought, scope).stream().map(KeyedHash::encoded).toList();
  var row = repository.findByAnyIndex(indexes);
  try (var original = protection.decrypt(EncryptedValue.parse(row.ciphertext()), scope, row.id())) {
    original.use(chars -> process(chars));
  }
}
```

El ejemplo usa un repositorio de la aplicación. `customerId` debe ser estable y el tenant debe
proceder de la identidad autorizada. El descifrado reconstruye el contexto desde esa información;
no confía en el tenant ni en el propósito declarados por una fila manipulada.

`protect` devuelve `EncryptedValue` y `KeyedHash`. Sus representaciones de diagnóstico se
ocultan; `encoded()` es una salida explícita para persistencia. Un conversor que cifra texto
plano no debe volver a cifrar `EncryptedValue`. `EncryptedValue.parse` valida la representación;
solo el descifrado verifica su autenticidad. Las columnas deben admitir el envelope completo:
el índice versionado no es un hash hexadecimal fijo de 32 caracteres.

`KeyedHash.parse` lee la representación persistida `axi1:keyId:profile:version:mac`, con perfil
UTF-8 codificado en Base64 URL y MAC completo de 32 bytes. Exige la representación canónica,
sin padding, versiones alternativas ni truncamientos. Su límite de entrada es 1500 caracteres;
el perfil admite hasta 1024 bytes. La sintaxis no autentica un índice ni acredita su ámbito.
`FieldEncryptor` y `EncryptedValue` usan el mismo parser de `axz1`: identificador válido,
Base64 URL canónico y payload acotado. `isEncrypted` comprueba ese formato, no su autenticidad.

También existen `encrypt`, `index`, `candidates` y `decrypt` por separado. Las primitivas
`FieldEncryptor` y `EnvelopeEncryptor` pueden utilizarse sin configurar HMAC. `BlindIndex`
puede usarse sin cifrado ni almacenamiento. La fachada completa recibe ambas capacidades.

El cifrado conserva el original, incluidos sus ceros iniciales, espacios y Unicode. Solo el
índice aplica la canonicalización declarada. `dniPe` exige ocho dígitos ASCII y no elimina ceros;
`identity` conserva exactamente el valor. Otra jurisdicción o una normalización distinta necesita
un perfil explícito y versionado. Unicode malformado se rechaza, no se reemplaza silenciosamente.

Las claves de AES y HMAC deben ser diferentes y resolverse desde el gestor de secretos. Para
rotación, se escribe con la clave actual y se consulta con los candidatos de las claves de
lectura vigentes. Se migran las filas antes de retirar una clave. Cambiar la canonicalización
también requiere migración. AES-GCM utiliza un IV aleatorio: dos cifrados de un mismo valor
pueden diferir, mientras que su índice es estable bajo el mismo ámbito, perfil y clave.

La integración Spring resuelve material Base64 mediante `SecretValue.base64Bytes`, sin
convertirlo a otro `String`; cierra cada secreto resuelto y borra los buffers temporales incluso
si falla el arranque. `StaticKeyProvider.fromBytes` y `BlindIndex` copian sus entradas: quien
las proporcionó conserva su responsabilidad de borrarlas. Ambos componentes se cierran con el
contexto y borran sus copias, sin invalidar las claves de otro contexto. Se admiten como máximo
16 claves de lectura; AES exige 32 bytes y HMAC de 32 a 1024 bytes.

`axiz.crypto.field.encryptions`, con etiqueta `keyId`, cuenta intentos de cifrado que alcanzan
la operación AES con contexto y clave válidos. El contador por clave es atómico y emite un aviso
al alcanzar 2³¹ usos; no bloquea. La métrica Spring registra las claves configuradas al arranque.
Un proveedor personalizado que cambie dinámicamente sus identificadores debe registrar también
la observación de esas claves. El contador es local a la instancia y no sustituye la política
global de rotación del despliegue.

El HMAC revela igualdad y frecuencia. Un DNI tiene un espacio de búsqueda pequeño: la clave
comprometida o un servicio que calcule índices sin límites permite ataques de diccionario.
Controla autorización, límites compartidos y auditoría si expones estas funciones por HTTP.
La búsqueda es por igualdad; no admite rangos ni coincidencias parciales.

`SecretValue` es cerrable, compara por identidad y ofrece `matches` para comparar contenido.
`use` entrega una copia y la borra al finalizar, incluso ante excepciones. `close` impide lecturas
posteriores. El borrado es de mejor esfuerzo: un `String` original, copias del consumidor y la JVM
pueden conservar datos. Ninguna de estas capacidades autoriza almacenar CVV, PIN u otros datos
de autenticación sensibles, ni acredita por sí sola cumplimiento PCI DSS.

Las cachés de secretos entregan copias independientes: cerrar un resultado no cierra el valor
compartido ni el de otra petición. Al retirar una entrada por reemplazo, LRU, expiración observada
en una lectura o cierre de la caché, borran el valor propio cuando terminan las copias pendientes.
La expiración es perezosa; no existe un hilo del core que borre valores exactamente al vencer el
TTL. El cierre impide nuevas lecturas y cancela cargas pendientes. Un resultado tardío de una
carga que no admite cancelación se descarta y borra cuando llega a la caché. El consumidor debe
seguir cerrando las copias que ya recibió.

Desde `axiz-framework`, las pruebas de las primitivas se ejecutan con:

```bash
./mvnw -pl core/axiz-core-crypto -am test
```
