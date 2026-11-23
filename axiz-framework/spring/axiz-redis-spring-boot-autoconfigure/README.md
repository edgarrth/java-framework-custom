# axiz-redis-spring-boot-autoconfigure

Protección de transporte Redis compartida, instalada por las capacidades que usan esta tecnología. No crea clientes de negocio ni agrega un nuevo selector de almacén.

Fuera de los entornos explícitos local/test/ci se exige TLS con verificación del certificado y del hostname. El texto plano local requiere además `axiz.transport.allow-insecure-data-stores=true`; el entorno por sí solo no basta. Los diagnósticos de fallo nunca incluyen URLs de conexión, credenciales ni nombres de secretos.

La configuración final del cliente se valida antes de la primera conexión. Las fábricas personalizadas que se conectan durante su construcción deben invocar la validación de transporte antes de construir o iniciar los clientes; un post-processor no puede deshacer la I/O previa. Lettuce usa SSL con `SslVerifyMode.FULL`; la protección se ejecuta antes de la inicialización de la fábrica. Los SSL bundles y los connection details se validan mediante la configuración final del cliente.

## Contrato de transporte

Los clientes gestionados exigen cifrado y verificación de certificado y de nombre de host. Las
opciones nativas finales se comprueban antes de la inicialización gestionada; los nombres de
propiedad por sí solos no prueban el transporte. `axiz.transport.allow-insecure-data-stores=true` solo admite un ambiente
`local`, `test` o `ci` explícito y nunca debilita la validación de producción. El material de confianza se
configura para cada destino.

Redis usa SSL por defecto. Lettuce debe usar la verificación completa del par; los customizers del cliente
no pueden desactivarla en producción. Los contenedores locales sin TLS requieren una sobrescritura SSL
explícita y la excepción local.
