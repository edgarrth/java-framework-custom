# Actualizar y renombrar servicios v2

Axiz 2.0.0 es la primera versión del framework. Los servicios usan el manifiesto schema 2,
los parents actuales y las estructuras del catálogo. Los prototipos que no tengan ese contrato
se regeneran desde sus contratos y conservan únicamente la lógica de negocio apropiada.

Para evolucionar un servicio v2, actualiza su parent/BOM con una versión aprobada mediante
`axiz upgrade --to <version-v2> --dry-run`, revisa el diff y ejecuta el comando sin `--dry-run`.
Prueba compilación, arquitectura, contratos e integraciones antes de desplegar. La versión del
servicio y la versión de sus eventos son independientes de la versión del framework.

El renombre mediante `--rename` conserva identidades persistentes: fuente y consumidor de eventos,
group-id Kafka, rutas de configuración y recursos de datos. Los cambios de nombre operativos
se coordinan con sus consumidores v2; no se mantienen consumidores de una línea anterior de Axiz.

La primera publicación valida firmas aprobadas; las evoluciones posteriores comparan contra una
versión v2 realmente publicada. La publicación usa los artefactos exactos del build validado,
con pipelines independientes de build y deploy.
