# Estructuras

Se eligen cuatro ejes independientes: arquetipo, stack, estructura y extensiones.
Cada servicio tiene exactamente una estructura. CQRS es una [extensión](../extensiones/cqrs.md).

| Estructura | Organización | Stack |
|---|---|---|
| [Hexagonal](hexagonal.md) | Dominio, casos de uso, puertos y adaptadores | servlet, reactive |
| [Layered](layered.md) | Controller, service y repository | servlet, reactive |
| [EDA](eda.md) | Reacciones y coreografía de procesos iniciados por eventos | servlet, reactive |
| [BFF](bff.md) | Composición y adaptación para canales | servlet, reactive |
| [Batch](batch.md) | Jobs reiniciables y procesamiento por lotes | servlet |

El catálogo valida las combinaciones por rol. `business` admite hexagonal o layered;
`core` y `gateway`, hexagonal; `ux`, bff; `support`, hexagonal o layered;
`event-processor`, eda o hexagonal; `batch`, batch.

Inbox, outbox, handlers tipados, reintentos y DLQ pertenecen a messaging y sus capacidades
asociadas. Un servicio hexagonal puede consumir eventos sin incorporar la estructura EDA.

Los paquetes del dominio no importan frameworks terceros; la aplicación utiliza los contratos
de Axiz. Las pruebas verifican que las dependencias apunten hacia dentro y que los adaptadores
permanezcan fuera del dominio. Ver [capas](../arquitectura/capas.md) y
[reglas](../calidad/arquitectura.md).
