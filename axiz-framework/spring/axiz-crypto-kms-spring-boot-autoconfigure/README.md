# axiz-crypto-kms-spring-boot-autoconfigure

Integración con AWS KMS para cifrado de sobre.

Este módulo es dueño de la API `pe.axiz.spring.crypto.kms`. Compone la capacidad criptográfica neutral
con el adaptador de AWS KMS y la integración compartida de identidad y transporte de AWS.

Para rotar entre claves KMS distintas, se configuran nombres de ruta inmutables y ARNs de clave inmutables:

```yaml
axiz:
  crypto:
    kms:
      key-id: arn:aws:kms:us-east-1:123456789012:key/22222222-2222-2222-2222-222222222222
      key-ring:
        generation1: arn:aws:kms:us-east-1:123456789012:key/11111111-1111-1111-1111-111111111111
        generation2: arn:aws:kms:us-east-1:123456789012:key/22222222-2222-2222-2222-222222222222
      data-key-max-age: 5m
      data-key-max-uses: 1000
```

Los ARNs del ejemplo son marcadores. `key-id` debe coincidir con exactamente una entrada del anillo. El
anillo acepta como máximo 16 rutas, rechaza alias mutables y exige ARNs de clave únicos. Ambas pilas
envuelven el mismo formato y seleccionan un único proveedor configurado para descifrar; no prueban cada
clave ni piden a KMS descifrar con una clave no especificada.

El identificador de ruta se autentica mediante el contexto de envoltura, de modo que cambiar el
identificador no redirige un texto cifrado válido aunque una configuración personalizada reutilice una
misma clave subyacente. El contexto contiene un propósito técnico y una ruta, nunca un token, PAN,
identificador de tenant ni vínculo con un registro. AWS explica por qué [el contexto de cifrado es dato de auditoría público](https://docs.aws.amazon.com/kms/latest/developerguide/encrypt_context.html)
y recomienda [la selección explícita de la clave al descifrar](https://docs.aws.amazon.com/kms/latest/APIReference/API_Decrypt.html).

El anillo se usa antes de escribir el primer sobre con ruta. Configurar solo `key-id` crea un proveedor
directo de una sola clave y un formato distinto de DEK envuelta; no existe un respaldo implícito de formato.
Convertir sobres directos en sobres con ruta es una migración de datos explícita con cifradores de origen y
destino, no un cambio de configuración gradual automático.

Cada ruta debe nombrar siempre la misma clave. La nueva ruta de lectura se instala en todos los lectores
antes de cambiar los escritores. Los cifradores se reconstruyen para cambiar su anillo de claves inmutable
y descartar las cachés anteriores. Todas las rutas de lectura necesarias se conservan hasta que la migración
de datos y la conciliación prueben que retirarlas es seguro. Esta integración aporta el enrutamiento;
desactivar o eliminar claves KMS requiere un proceso de retiro gobernado por separado que considere los
escritores activos, los registros persistidos, los respaldos y otros usuarios. Ningún bean ejecuta
operaciones de desactivación o eliminación en KMS.
