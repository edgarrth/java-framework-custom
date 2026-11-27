<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# jpa

PostgreSQL con JPA, Flyway, auditoría y columnas cifradas

```bash
axiz add jpa
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-jpa-spring-boot-starter` |
| Stacks del catálogo | servlet |
| Dependencias | `pe.axiz:axiz-kit-jpa` |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
spring.datasource.url: ${DB_URL:jdbc:postgresql://localhost:5432/app}
spring.datasource.username: ${DB_USERNAME:app}
spring.jpa.open-in-view: false
spring.jpa.hibernate.ddl-auto: validate
spring.flyway.enabled: true
```

## Según la persistencia del servicio

### Aurora en servlet (JDBC/JPA)

Sin configuración adicional.

## Llaves del ConfigMap por ambiente

- `spring.datasource.url`
- `spring.datasource.username`

## Uso

```java
@Entity class Order extends AuditableEntity { ... }
```

## Módulos

- [`axiz-jpa-spring-boot-autoconfigure`](../referencia/spring/axiz-jpa-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-crypto`](../referencia/core/axiz-core-crypto.md)
