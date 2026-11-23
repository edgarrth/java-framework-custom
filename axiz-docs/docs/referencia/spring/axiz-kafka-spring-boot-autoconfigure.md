<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-kafka-spring-boot-autoconfigure

Validación compartida del transporte Kafka efectivo para mensajería y auditoría. Se exige SSL o SASL_SSL y verificación del par y del hostname, salvo una excepción explícita local/test/ci (`axiz.transport.allow-insecure-data-stores=true`).

La configuración del productor, del consumidor y de administración se valida por separado, después de los customizers de Boot. Los clientes nativos creados por Axiz vuelven a validar justo antes de su construcción. `KafkaTransportSecurity.properties` conserva las sobrescrituras por cliente, los connection details y los SSL bundles; la ausencia del algoritmo de hostname usa el valor HTTPS seguro por defecto de Kafka, mientras que un valor vacío se rechaza. No se registran en el log configuraciones de conexión crudas ni credenciales.

Las fábricas personalizadas que se conectan dentro de su constructor deben invocar la validación antes de la construcción; el post-procesamiento no puede deshacer la I/O previa.

## Contrato de transporte

Los clientes gestionados exigen cifrado y verificación de certificado y de nombre de host. Las
opciones nativas finales se comprueban antes de la inicialización gestionada; los nombres de
propiedad por sí solos no prueban el transporte. `axiz.transport.allow-insecure-data-stores=true` solo admite un ambiente
`local`, `test` o `ci` explícito y nunca debilita la validación de producción. El material de confianza se
configura para cada destino.

Kafka usa SSL por defecto. Se conservan los SSL bundles y los `KafkaConnectionDetails` por cliente. Los
productores y consumidores nativos deben validar el mapa final con `KafkaTransportSecurity.validate` antes
de construir su cliente. La identificación del endpoint no puede estar vacía en producción. Mensajería
agrega su identidad IAM a las fábricas gestionadas de productores y consumidores y al cliente de administración.
