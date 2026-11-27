# Actualizar y renombrar servicios

Los servicios usan el manifiesto schema 2, los parents del framework y las estructuras del
catálogo. Un proyecto sin ese manifiesto se regenera desde sus contratos y conserva únicamente
su lógica de negocio.

Para actualizar un servicio, cambia su parent/BOM a una versión aprobada mediante
`axiz upgrade --to <version> --dry-run`, revisa el diff y ejecuta el comando sin `--dry-run`.
Prueba compilación, arquitectura, contratos e integraciones antes de desplegar. La versión del
servicio y la versión de sus eventos son independientes de la versión del framework.

El renombre mediante `--rename` conserva identidades persistentes: fuente y consumidor de eventos,
group-id Kafka, rutas de configuración y recursos de datos. Los cambios de nombre operativos
se coordinan con sus consumidores.

Las reglas de capas están en [capas y ejecución](../arquitectura/capas.md).
La publicación valida las firmas aprobadas de la API pública y la compara con la versión
publicada anterior. Se publican los artefactos exactos del build validado,
con pipelines independientes de build y deploy.

## Representaciones de documentos

`Ruc.masked()` y su diagnóstico no publican el RUC completo. DNI/RUC revelan como máximo
el 25 % final y cuatro caracteres; documentos menores a ocho caracteres se ocultan completos.
Usar el acceso explícito al valor solamente en el flujo autorizado, no `toString()` para
persistencia, integración o comparación. `IdentifierMasks` mantiene esta política compartida
con el enmascaramiento de datos sensibles.
