# Crear un servicio

Esta guía lleva un servicio nuevo desde su contrato hasta correr en tu máquina con sus pruebas en
verde. Toma unos minutos; el resultado es el mismo que produce el initializr web.

## Requisitos

- JDK 25 (Maven viene con el wrapper `./mvnw` de cada proyecto).
- Docker, para el entorno local (`compose.yaml`) y las pruebas con Testcontainers.
- La CLI `axiz` (binario nativo de la página de releases de `axiz-tools`, o `java -jar axiz-cli.jar`).
- Acceso al Nexus corporativo en `~/.m2/settings.xml` (mirror y credenciales; nunca en el proyecto).

```bash
axiz --version
axiz catalog            # arquetipos y capacidades disponibles en esta versión
```

## 1. Escribir el contrato

Los arquetipos que exponen REST (`experience`, `business`, `process`, `integration`, `crud`)
requieren un contrato **OpenAPI 3.1**. Revísalo antes de generar:

```bash
axiz contract lint payments-business.yaml
```

Las reglas de axiz (las mismas del ruleset de Spectral que corre en CI) piden `operationId` en
lowerCamelCase (nombra el caso de uso generado), rutas en kebab-case con versión (`/v1/...`),
errores 4xx/5xx en `application/problem+json`, ejemplos en los cuerpos (se usan en las pruebas de
contrato), `page` y `size` en las colecciones, `Idempotency-Key` en los POST no idempotentes y
ningún número de documento o tarjeta como query parameter. Los eventos se describen en AsyncAPI 3
(`--asyncapi`).

## 2. Generar el proyecto

```bash
axiz new payments-business \
  --archetype business \
  --framework hexagonal \
  --persistence aurora \
  --openapi payments-business.yaml \
  -c outbox -c sequence
```

O `axiz new -i` para que pregunte cada valor. Cuando no indicas estilo, stack o persistencia se
usa el primero de cada lista del [catálogo de arquetipos](../arquetipos/catalogo.md). El
generador valida la combinación y el contrato antes de escribir nada.

| Opción | Valores |
|---|---|
| `--archetype` | `experience`, `business`, `process`, `integration`, `event-processor`, `batch`, `crud`, `library`, `starter` |
| `--framework` | `layered`, `hexagonal`, `cqrs`, `eda`, `bff`, `batch` (según el arquetipo) |
| `--stack` | `servlet` (virtual threads, por defecto) o `reactive` (WebFlux) |
| `--persistence` | `none`, `aurora` (JPA en servlet, R2DBC en reactivo), `dynamodb` |
| `-c` | capacidades extra (repetible), ver [Capacidades](../capacidades/index.md) |

## 3. Qué se generó

```text
payments-business/
├── pom.xml                         hereda axiz-parent-service (versiones y gates)
├── mvnw, .mvn/                     wrapper de Maven
├── .axiz/project.yaml              manifiesto: arquetipo, estilo, capacidades, versión de axiz
├── src/main/openapi/openapi.yaml   contrato (fuente de verdad)
├── src/main/java/…                 paquetes del estilo, controladores esqueleto (501)
├── src/main/resources/             application.yaml (solo valores por defecto), mensajes, migraciones
├── src/test/java/…                 ArchitectureTest, prueba de contexto, ejemplos del contrato
├── compose.yaml, local/            dependencias locales y secretos de desarrollo
├── deploy/helm/payments-business/  chart con values-dev/qa/prod, ExternalSecret, RBAC, NetworkPolicy
├── .github/workflows/              ci.yaml, cd.yaml y release.yaml sobre axiz-workflows
```

Los modelos de request y response se generan al compilar desde el contrato y nunca se versionan;
los controladores se generan **una vez** como esqueletos que responden `501 Not Implemented` hasta
que implementas cada operación (ver [Contrato primero](../herramientas/contratos.md)).

## 4. Compilar y probar

```bash
./mvnw verify
```

`verify` corre las pruebas unitarias, las reglas de arquitectura
del estilo (`ArchitectureTest`) y la prueba de arranque contra dependencias reales en
contenedores. `./mvnw -Pquality verify` agrega formato (Spotless), convenciones (Checkstyle), errores y nulos
(Error Prone + NullAway), SpotBugs/FindSecBugs y los umbrales de cobertura: es el gate que corre
CI en cada PR.

## 5. Correr en local

```bash
local/dev-secrets.sh                 # secretos aleatorios solo de desarrollo en local/secrets
docker compose up -d                 # o: axiz local up
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Detalles del entorno (Keycloak, LocalStack, Grafana, WireMock) en [Entorno local](entorno-local.md).

## 6. Implementar la primera operación

En el estilo hexagonal cada operación del contrato tiene su caso de uso en
`application/usecase`. Implementa la regla de negocio en el dominio, el caso de uso orquesta y el
controlador solo adapta:

```java
import pe.axiz.framework.hexagonal.annotation.UseCase;

@UseCase
public class CreatePaymentOrderUseCase
    implements pe.axiz.framework.hexagonal.UseCase<CreatePaymentOrderUseCase.Input, PaymentOrder> {

  public record Input(CreatePaymentOrderRequest body) {}

  private final PaymentOrders orders;   // puerto de salida, implementado en adapter/out

  public CreatePaymentOrderUseCase(PaymentOrders orders) {
    this.orders = orders;
  }

  @Override
  public PaymentOrder execute(Input input) {
    PaymentOrder order = PaymentOrder.create(input.body());   // reglas en el dominio
    return orders.save(order);
  }
}
```

El caso de uso corre en una transacción con su span; los eventos de dominio que registre el
agregado se publican al confirmar (o se encolan en el outbox dentro de la misma transacción).

Los errores de negocio se lanzan con una definición del catálogo del servicio
(`axiz generate error-code limit-exceeded`) y salen como `application/problem+json`
(ver [Errores](../guias/errores.md)).

## 7. Subirlo

Al abrir el pull request corren los checks compartidos y el build con todos los gates. Al hacer
merge a `main` se construye la imagen firmada y se despliega a dev y qa; un tag `v*` (creado por el
PR de release) despliega a producción con aprobación. Ver [CI/CD](../operacion/ci-cd.md).
