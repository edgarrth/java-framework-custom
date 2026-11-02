# Arquetipos

El arquetipo define el rol y los límites de un servicio. Es independiente de su estructura,
stack de ejecución y extensiones. El [catálogo](catalogo.md) se genera desde el mismo modelo
que utilizan la CLI e Initializr.

| Arquetipo | Responsabilidad | Estructuras |
|---|---|---|
| `business` | Reglas de negocio, procesos de dominio y datos propios | hexagonal, layered |
| `core` | Anticorrupción frente a sistemas internos core | hexagonal |
| `gateway` | Frontera frente a sistemas externos | hexagonal |
| `ux` | Composición y adaptación para canales | bff |
| `support` | Capacidad transversal sin dependencia de dominios | hexagonal, layered |
| `event-processor` | Reacción a eventos sin API REST de negocio | eda, hexagonal |
| `batch` | Procesos por lotes activados por el planificador | batch |

CQRS es opcional sobre hexagonal para `business` y `event-processor`.
`library` y `starter` son tipos de proyecto de plataforma; no son arquetipos de servicio.

## Comunicación

`ux` llama a `business` y `support`; nunca llama directamente a `core` o `gateway`.
`business`, `event-processor` y `batch` pueden llamar a `business`, `core`, `gateway` y `support`.
Se prefieren eventos para propagar cambios entre dominios; REST sirve para consultas y pasos de saga.

`core` llama exclusivamente a los sistemas internos core que integra. `gateway` llama a sistemas
externos. `support` puede llamar a sus proveedores de commodity, como SMTP, SMS o push, y no
llama a dominios de negocio. Estos tres bordes no llaman a otros servicios Axiz.
Los procesadores y batch se activan por eventos o planificación, no por REST.

Los contratos OpenAPI declaran `info.x-axiz-archetype`. La generación de clientes verifica
la matriz de llamadas. Una integración técnica se identifica como `internal-system`,
`external-system` o `commodity-provider`, en lugar de inventar otro arquetipo de servicio.

## Elección

Usa `business` para reglas y datos de un dominio, incluido CRUD o una saga. Elige `core` o `gateway`
según si el sistema integrado pertenece a la empresa. Usa `ux` para un canal, `support` para
una capacidad reutilizable, `event-processor` cuando la entrada es un evento y `batch` para un job.
Los nombres describen la capacidad; no añaden sufijos como `-business`, `-crud` o `-integration`.
