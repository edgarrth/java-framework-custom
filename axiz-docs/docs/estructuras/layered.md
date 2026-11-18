# Layered

Para tablas de mantenimiento, catálogos y recursos con poca lógica. Es una estructura de `business` o `support` cuando no se necesita separar
un modelo de dominio rico. Admite servlet y reactive con persistencia nativa del stack.

```text
<base>/
├── controller/     REST (esqueletos generados desde el contrato) y mappers de API
├── service/        reglas y transacciones; catálogo de errores del servicio
├── repository/     acceso a datos (JPA, R2DBC o DynamoDB)
└── model/          entidades
```

El starter no trae persistencia. Con Aurora, el servicio declara `axiz-jpa-spring-boot-starter`
(capacidad `jpa`, que habilita `JpaCrudService`); con DynamoDB, `axiz-dynamodb-spring-boot-starter`.
Así, un servicio sobre DynamoDB arranca sin `DataSource`.

**Reglas** (`LayeredRules`): el controlador no es accedido por nadie, el servicio solo por el
controlador y el repositorio solo por el servicio; los `*Controller` viven en `controller` y los
`*Repository` en `repository`.

## API

| Tipo | Para qué |
|---|---|
| `QueryService<T, I>` | `find`, `get` (404 si no existe), `list(PageRequest)` |
| `CommandService<RequestT, ResponseT, I>` | `create`, `update`, `delete` |
| `CrudService<RequestT, ResponseT, I>` | ambos |
| `JpaCrudService<E, RequestT, ResponseT, I>` | CRUD autorizado con entidad interna, DTO de petición y DTO de respuesta |
| `CrudController` | endpoints REST de un recurso simple sobre un `CrudService` |
| `pe.axiz.test.support.layered.InMemoryCrudService` | fixture de `axiz-test-support`, solo en alcance de pruebas |

Cuando el recurso tiene reglas propias (duplicados, límites, borrado lógico, caché) el servicio se
escribe a mano sobre el repositorio, como `BeneficiariesService` en el demo
(`beneficiaries-crud`, DynamoDB con paginación por cursor, borrado lógico, bloqueo optimista y
caché).

## Autorización, entrada y concurrencia

El controlador recibe exclusivamente un DTO de petición y devuelve un DTO de respuesta.
No exponga la entidad JPA ni acepte un propietario, saldo, identificador o versión de entidad
por copia genérica de campos. `newEntity` construye una instancia nueva con identidad y
propietario decididos por el servidor; el framework llama a `persist`, nunca a `merge`.
`apply` copia únicamente los atributos editables y `toResponse` controla lo que se devuelve.
El DTO valida también el alfabeto aceptado por el negocio y el almacén. Por ejemplo, un nombre
de mantenimiento puede usar `@NotBlank`, `@Size` y `@Pattern(regexp = "[^\\p{Cntrl}]*")` para
rechazar caracteres de control antes de llegar a PostgreSQL. No conviertas cualquier excepción
de base de datos en 400: también podría ser un defecto del servicio.

El constructor de `JpaCrudService` recibe la clase de entidad, el `JpaRepository`, el
`JpaSpecificationExecutor`, el `EntityManager` y el nombre lógico del recurso. Exige un
mapeo JPA efectivo de versión, también si se hereda de `BaseEntity`. El `EntityManager`
debe ser el proxy transaccional de Spring, nunca una sesión compartida entre peticiones.

Cada subclase implementa obligatoriamente:

- `authorize(action, entity)`: autorización funcional y por objeto; `entity` es null
  para CREATE y LIST. Lance `AccessException` para denegar. READ, UPDATE y DELETE
  responden como objeto ausente si el solicitante no puede acceder a él.
- `listScope()`: especificación que limita las filas visibles al solicitante autenticado.
  No se acepta null. Los totales y la paginación se calculan con ese mismo filtro.
- `sortableFields()`: nombres de campos permitidos; otra ordenación es un error de entrada.
- `expectedVersion(request)`: versión esperada de actualización, del mismo tipo que la
  versión JPA. Ausencia es un error de entrada; discrepancia responde 409.

La actualización usa bloqueo optimista JPA y hace flush antes de construir la respuesta,
para devolver la versión nueva y detectar escrituras concurrentes. El commit sigue siendo
parte de la transacción del servicio. Configure la autorización de rutas mediante
`axiz.security.authorization.rules`; una anotación de clase en un controlador heredado no
sustituye esa configuración ni la autorización por objeto del servicio.

Estas operaciones sirven para mantenimiento. Débitos, transferencias y límites financieros
necesitan comandos con sus propias invariantes transaccionales, además de autorización.
El estilo layered admite ambos stacks; estas dos clases auxiliares (`JpaCrudService` y
`CrudController`) son servlet/JPA, no una fachada de operaciones bloqueantes para WebFlux.
