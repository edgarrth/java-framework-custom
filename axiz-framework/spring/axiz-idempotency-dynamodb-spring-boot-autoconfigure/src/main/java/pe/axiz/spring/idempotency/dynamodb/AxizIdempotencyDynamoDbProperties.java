package pe.axiz.spring.idempotency.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración de dynamodb que conserva sus claves de configuración públicas. */
@ConfigurationProperties("axiz.idempotency")
public record AxizIdempotencyDynamoDbProperties(@DefaultValue("axiz-idempotency") String table) {
  public AxizIdempotencyDynamoDbProperties {
    if (table == null || table.isBlank())
      throw new IllegalArgumentException("axiz.idempotency.table must not be blank");
  }
}
