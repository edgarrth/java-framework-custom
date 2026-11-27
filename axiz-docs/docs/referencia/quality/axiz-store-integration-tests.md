<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-store-integration-tests

Módulo interno, nunca publicado: no es una dependencia para los servicios.

Ejecuta los contratos de almacenes de `axiz-store-contract-tests` contra backends reales en
contenedores (Testcontainers): PostgreSQL para el inbox JDBC y R2DBC, Valkey para el almacén de
idempotencia Redis y LocalStack DynamoDB para los almacenes de idempotencia e inbox de DynamoDB.

```bash
./mvnw -pl quality/axiz-store-integration-tests -am verify -Daxiz.it.requireDocker=true
```

Sin Docker, las pruebas se omiten en local; CI pasa `-Daxiz.it.requireDocker=true`, de modo que la
ausencia de Docker hace fallar el build.

Los fixtures de aceptación adicionales incluyen:

- `CrudAcceptanceIT`: HTTP real con Tomcat y JWT firmados, dos recursos de mantenimiento y PostgreSQL;
  propiedad de objetos, listas acotadas por ámbito, campos protegidos, versiones optimistas y entrada
  mal formada.
- `VaultAcceptanceIT`: límites de Spring servlet y nativo, JWT firmados, LocalStack DynamoDB/KMS/SQS,
  presupuestos autenticados en Valkey, auditoría sellada estricta, separación de tenants y fallo
  después del commit.
- `LocalStackDynamoDbStoresIT` y `ValkeyIdempotencyStoreIT`: repetición real con el proveedor, bytes
  persistidos protegidos, CAS concurrente, binding de la petición y rechazo cuando la protección está
  desactivada.
- Aceptación de outbox y saga con PostgreSQL JDBC y R2DBC: límites transaccionales, almacenamiento de
  texto cifrado, estado durable de la cadena de auditoría y comprobación independiente de fallos.

Para centrarse en un fixture se usa `-Dtest=CrudAcceptanceIT -Dsurefire.failIfNoSpecifiedTests=false`
con el comando anterior. El build completo `-Pquality clean install -Daxiz.it.requireDocker=true`
también ejecuta la verificación requerida y los controles de mutación y cobertura. LocalStack no
demuestra el IAM de AWS desplegado, la configuración TLS, la restauración de respaldos ni el
cumplimiento PCI DSS.
