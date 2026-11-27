# Crear el primer servicio

Instalar JDK 25, Docker y la CLI `axiz`. El servicio se genera desde un contrato y el catálogo
de la versión instalada; `axiz catalog archetypes` y `axiz catalog capabilities` muestran las
combinaciones disponibles.

## Contrato y generación

Los roles que exponen REST necesitan un contrato OpenAPI 3.1 en el que cada operación declare su
acceso: `security` con un esquema OAuth2/OpenID Connect (con o sin scopes) o bearer, heredado de la
raíz o propio, o `security: []` para un acceso público explícito. `axiz contract lint`, `axiz
doctor`, el Initializr y `axiz new` aplican las mismas reglas. Validarlo antes de generar:

```bash
axiz contract lint payments.yaml
axiz new payment-orders --archetype business --structure hexagonal \
  --persistence aurora --openapi payments.yaml -c outbox -c messaging-kafka -c sequence
cd payment-orders
axiz doctor
```

`axiz new -i` permite la selección interactiva. CQRS se selecciona con `--extension cqrs`
sobre hexagonal; es una extensión, no una estructura independiente. El
[catálogo de arquetipos](../arquetipos/catalogo.md) indica estructuras, stacks y persistencias
admitidos para cada rol: business, core, gateway, ux, support, event-processor y batch.
También permite crear library y starter.

| Parámetro | Uso |
|---|---|
| `--archetype` | Rol del proyecto según el catálogo |
| `--structure` | Organización layered, hexagonal, EDA, BFF o batch admitida por el rol |
| `--extension` | Extensiones opcionales admitidas por la estructura |
| `--stack` | Servlet o reactive cuando el rol lo permita |
| `--persistence` | none, aurora o dynamodb según el catálogo; Aurora usa JPA/JDBC en servlet y R2DBC en reactive |
| `-c` | Capacidad adicional, repetible |
| `--group`, `--package` | Coordenadas Maven y paquete base |

## Contenido del proyecto

El README generado describe las opciones y capacidades activadas. `axiz.yaml` conserva la
configuración del proyecto; `contracts` contiene los contratos; `src/main` contiene el código
y la configuración; `src/test` incluye pruebas de arquitectura e integración. El chart de
`deploy/helm` y los workflows de `.github/workflows` separan construcción y despliegue.

En hexagonal, las reglas viven en `domain`, los casos de uso y puertos en `application`, y
las entradas y tecnologías en `adapter`. El dominio puede usar contratos de Axiz sin importar
Spring, clientes de tecnología o tipos de persistencia. Consultar
[capas](../arquitectura/capas.md) y [reglas de arquitectura](../calidad/arquitectura.md).

## Compilar y ejecutar

```bash
./mvnw verify
./mvnw -Pquality verify
local/dev-secrets.sh
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Los secretos generados son solo de desarrollo. Los entornos desplegados utilizan el gestor
de secretos configurado. Ver [entorno local](entorno-local.md). Las operaciones REST generan
modelos durante el build y esqueletos de controlador que deben implementarse; consultar
[contrato primero](../herramientas/contratos.md).

## Incorporar capacidades

```bash
axiz add cache
axiz generate usecase FindPayment
axiz doctor
```

Revisar la [guía de evolución](evolucionar.md), el [catálogo de capacidades](../capacidades/index.md)
y las [pruebas](../guias/pruebas.md) para integrar comportamiento y validar el servicio.
