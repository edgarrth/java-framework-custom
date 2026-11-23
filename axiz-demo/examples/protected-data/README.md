# Datos protegidos: ejemplos ejecutables de consumidor

Se ejecuta desde `axiz-demo` con JDK 25, los artefactos de Axiz instalados y Docker:

```sh
./mvnw -pl examples/protected-data -Pquality verify
```

Este módulo, solo de pruebas, aprovisiona recursos desechables de LocalStack DynamoDB, KMS, SQS y
Secrets Manager, además de Valkey autenticado. No contacta AWS de forma remota y no es un servicio de
bóveda desplegable. El reactor normal de la demo lo ejecuta; la ausencia de Docker lo hace fallar.

`ProtectedDataExampleTest` compone las integraciones reales de proveedores de Spring y muestra:

- Tokenización de PAN y DNI en una bóveda central cifrada en DynamoDB, búsqueda repetida y
  revelación autorizada. Los resultados visibles para el cliente contienen un token y los metadatos
  permitidos; el texto cifrado y las huellas de igualdad permanecen dentro de la bóveda.
- Protección independiente del campo DNI: se recibe el texto cifrado más un HMAC con clave, se
  guardan ambos en una tabla DynamoDB propia de la aplicación, se busca por el HMAC y se descifra con
  el binding de tenant, dominio y fila estable. Este camino no invoca la tokenización.
- Claves separadas de cifrado e índice resueltas desde Secrets Manager, cifrado de sobre con KMS,
  presupuestos distribuidos autenticados y registros de auditoría en SQS sellados con HMAC y confirmados.
- `VaultMaintenanceRecipe` muestra la autorización administrativa, la rotación y reindexación del
  índice, la poda de referencias de búsqueda obsoletas, la rotación de la clave de envoltura de KMS y
  el recifrado, el retiro protegido de claves y la expiración por retención. Los tokens se mantienen
  estables durante la rotación.

La rotación es un protocolo operativo. Se aprovisionan las claves nuevas y se despliegan lectores que
conocen ambas versiones antes de activar una versión de escritura nueva. Se detienen los escritores de
la versión anterior, se activa la versión, se reemplaza la configuración inmutable de runtime con los
nuevos escritores y se reanuda el tráfico. La bóveda rechaza a los escritores desfasados. Se ejecutan
páginas de migración acotadas hasta que no queden registros de la versión anterior; ese estado se
demuestra antes de retirar la versión anterior. La poda de índices usa el contrato de preparación y
prueba de mantenimiento. Desactivar una clave externa de KMS también debe considerar otros keysets,
los respaldos y las obligaciones de retención.

Las llamadas administrativas usan un principal separado con solo el scope de mantenimiento y un tenant,
keyset y reloj confiables. La retención se muestra con un reloj de prueba controlado, nunca con una
fecha que entregue un llamador público. Un resultado de mantenimiento incierto se envía a una cola de
conciliación dedicada con la acción, el ID de operación, el keyset y la versión, y el ámbito. Esa cola
restringida puede necesitar el token completo para hacer la conciliación; los logs de la aplicación
conservan solo diagnósticos redactados. Los operadores deben consumirla y conciliarla antes de dar por
completo un resultado incierto.

Para consumidores remotos de aplicación, ver
[`RemoteTokenClientRecipeTest`](../provider-recipes/src/test/java/pe/axiz/demo/providers/RemoteTokenClientRecipeTest.java).
Muestra el cliente público de tokens en ambos stacks web. `ReactiveVaultRecipe` usa proveedores nativos asíncronos de DynamoDB, KMS, Redis y auditoría
en SQS confirmada, comprueba el aislamiento por tenant y cierra los resultados sensibles dentro de los
callbacks de finalización. Este módulo se concentra en los proveedores reales de la bóveda y en las
operaciones independientes de cifrado e índice.

Las identidades se entregan a propósito en el límite **interno** confiable `VaultOperations`. Estos
ejemplos no implementan autenticación JWT ni exponen una API HTTP. El `VaultAcceptanceIT` del framework
ejercita por separado los límites de JWT firmado y HTTP. Una bóveda desplegada debe verificar la
identidad antes de construir `TokenCaller` y configurar TLS, IAM restringido, custodia y rotación de
claves, retención y operaciones de conciliación. La aceptación funcional con LocalStack no es una
certificación PCI DSS ni una prueba de la aplicación de IAM en AWS.

El fixture de prueba crea las tablas y los registros iniciales de estado de claves como propietario de
la infraestructura. El proveedor de runtime los verifica y no crea tablas. La limpieza del fixture
cierra los clientes, las claves y los contenedores; la salida de las pruebas nunca imprime valores ni
material secreto.
