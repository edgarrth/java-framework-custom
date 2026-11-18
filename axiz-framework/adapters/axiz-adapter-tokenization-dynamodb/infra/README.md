# Infraestructura de la bóveda

`vault.cloudformation.json` define la tabla central de esquema 1, sin índices secundarios,
réplicas ni TTL. Activa cifrado SSE con una clave KMS del operador, PITR de 1–35 días,
protección de borrado y conservación de recursos al eliminar o sustituir el stack.
No se despliega al arrancar la aplicación. El nombre, cuenta, región y claves son del operador.

La clave SSE es distinta de las claves de sobres y del material HMAC. El stack recibe ARN
exactos de claves existentes; no crea secretos ni concede administración de claves.
Sus políticas administradas se entregan como outputs y **no se adjuntan automáticamente**.
`EnvelopeKeyContexts` enumera los contextos exactos del anillo de claves, por ejemplo
`axiz-keyring-v1/vault-v1/cHVycG9zZTp0b2tlbi12YXVsdA` para la ruta `vault-v1`. El último
segmento codifica `purpose:token-vault` en base64url sin padding. Incluir las rutas lectoras
retenidas y actualizar este parámetro al rotar; usar solamente `purpose:token-vault` no
coincide con el contexto autenticado que envía el proveedor. Nunca incluir valores del cliente.
Adjuntar cada una a identidades distintas, con relaciones de confianza restringidas:

| Identidad | Políticas de este stack | Permisos principales |
| --- | --- | --- |
| Servicio de bóveda | OperationalPolicy, EnvelopeUsePolicy | Lectura, condiciones, altas y bajas transaccionales de T/F |
| Bootstrap y rotación | MaintenancePolicy, EnvelopeUsePolicy | Escaneo, transiciones C/E, reservas R/I, migración T/F |
| Retención | RetentionPolicy | Escaneo y borrado transaccional T/F; sin uso de claves ni altas |
| Aplicación consumidora | Ninguna | Solo cliente HTTPS y su identidad JWT |
| Administrador de claves/infraestructura | Configuración del operador | Administración fuera del proceso de bóveda |

Las políticas de tabla deniegan acciones ajenas al conjunto necesario, mutaciones fuera de
transacciones y eliminación de controles/reservas. El rol operativo tampoco puede escribir
controles. No adjuntar simultáneamente políticas de operación y mantenimiento a una identidad:
la denegación explícita de operación impediría el mantenimiento. Los permisos de auditoría,
Redis, lectura del secreto HMAC y la política de las claves KMS se configuran por separado,
limitados a los recursos concretos de cada despliegue. Ningún permiso IAM por sí solo expresa
la fecha de expiración o las reglas de negocio del protocolo; eso se exige en el código.

AWS autoriza cada ítem transaccional mediante `GetItem`, `ConditionCheckItem`, `PutItem` o
`DeleteItem`; `TransactWriteItems` es una operación, no una acción IAM. Se limita la operación
envolvente y las claves de partición siguiendo la
[documentación de transacciones](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis-iam.html)
y de [LeadingKeys](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/specifying-conditions.html).

## Provisionar y validar

Revisar un change set de CloudFormation con `CAPABILITY_IAM`, los parámetros explícitos y la
identidad de infraestructura de la organización. No ejecutar el stack con el rol operativo.
Validar la plantilla con `cfn-lint vault.cloudformation.json`. La prueba `verify_iam.py` invoca
el simulador **real de AWS IAM** con las políticas renderizadas y casos permitidos/denegados:

```sh
python3 verify_iam.py --region us-east-1 --table axiz-token-vault \
  --storage-key-arn arn:aws:kms:us-east-1:123456789012:key/EXPLICIT-STORAGE-KEY \
  --envelope-key-arn arn:aws:kms:us-east-1:123456789012:key/EXPLICIT-ENVELOPE-KEY \
  --envelope-route vault-v1 \
  --output ./iam-evidence.json
```

Requiere credenciales AWS y `iam:SimulateCustomPolicy`; falla si faltan permisos o respuestas.
Incluye 48 casos DynamoDB y nueve KMS: contexto enrutado, ausencia/cambio de contexto,
clave ajena y operaciones administrativas prohibidas.
La simulación de estas políticas no prueba las SCP, permissions boundaries, políticas de
recurso, confianza ni permisos efectivos de las identidades desplegadas. La aceptación del
entorno exige además pruebas reales con los roles y recursos finales. LocalStack no valida IAM.

## Ciclo de vida y retención

Inicializar C/E con `VaultRotator.initialize` e `initializeWrapping` desde el job privilegiado.
No insertar controles manualmente ni borrar R/I: impiden reinicializar o reutilizar claves.
Cambiar escrituras, migrar y retirar lectores con el protocolo de rotación, comprobando
auditoría, conciliación y ausencia de referencias antes de deshabilitar claves externas.

Ejecutar `VaultRetention.purge` desde un trabajador síncrono independiente, con autoridad
propia y `VaultMaintenanceAccess` que autorice keyset y ámbito de cada registro. Cada llamada
visita como máximo 100 registros, tiene presupuesto temporal, corte obtenido del reloj
inyectado y auditoría confirmada antes y después de cada eliminación. Conservar el cursor
solo después de éxito; recorrer nuevamente los conflictos. El resultado incierto se concilia
sin reintento automático. No se descifra ningún valor para purgarlo. Un registro vigente,
una versión cambiada o una referencia con otro propietario no se elimina por fuerza.

Eliminar datos activos no elimina copias PITR, backups, exportaciones o auditoría. El operador
define su retención, acceso y recuperación conforme a sus obligaciones. Restaurar primero en
aislamiento, conservar C/E/R/I y claves lectoras, conciliar altas/bajas posteriores al punto
restaurado y ejecutar retención antes de habilitar consumidores. Una restauración nunca
autoriza volver a revelar valores que ya debían estar eliminados. El stack y los tests no
constituyen una certificación PCI DSS.
