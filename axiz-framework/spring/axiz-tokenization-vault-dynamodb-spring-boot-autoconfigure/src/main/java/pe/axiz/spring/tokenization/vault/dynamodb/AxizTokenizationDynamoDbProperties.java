package pe.axiz.spring.tokenization.vault.dynamodb;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit pre-provisioned table; finite per-request deadline. No auto-create or TTL switch. */
@ConfigurationProperties("axiz.tokenization.vault.dynamodb")
public record AxizTokenizationDynamoDbProperties(
    String table, @DefaultValue("2s") Duration requestTimeout) {
  public AxizTokenizationDynamoDbProperties {
    if (table == null || !table.matches("[A-Za-z0-9_.-]{3,255}"))
      throw new IllegalArgumentException("An explicit token vault table is required");
    if (requestTimeout == null
        || requestTimeout.compareTo(Duration.ofMillis(1)) < 0
        || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException(
          "Token vault DynamoDB timeout must be between 1ms and 30s");
  }
}
