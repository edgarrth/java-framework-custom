# Layered

Para tablas de mantenimiento, catálogos y recursos con poca lógica. Es el estilo del arquetipo
`crud` y una opción de `business` cuando el dominio es casi inexistente.

```text
<base>/
├── controller/     REST (esqueletos generados desde el contrato) y mappers de API
├── service/        reglas y transacciones; catálogo de errores del servicio
├── repository/     acceso a datos (JPA o DynamoDB)
└── model/          entidades
```

**Reglas** (`LayeredRules`): el controlador no es accedido por nadie, el servicio solo por el
controlador y el repositorio solo por el servicio; los `*Controller` viven en `controller` y los
`*Repository` en `repository`.

## API

| Tipo | Para qué |
|---|---|
| `QueryService<T, I>` | `find`, `get` (404 si no existe), `list(PageRequest)` |
| `CommandService<T, I>` | `create`, `update`, `delete` |
| `CrudService<T, I>` | ambos |
| `JpaCrudService` | `CrudService` sobre un repositorio Spring Data JPA |
| `CrudController` | endpoints REST de un recurso simple sobre un `CrudService` |
| `InMemoryCrudService` | para pruebas, prototipos y el perfil local |

Cuando el recurso tiene reglas propias (duplicados, límites, borrado lógico, caché) el servicio se
escribe a mano sobre el repositorio, como `BeneficiariesService` en el demo
(`beneficiaries-crud`, DynamoDB con paginación por cursor, borrado lógico, bloqueo optimista y
caché).
