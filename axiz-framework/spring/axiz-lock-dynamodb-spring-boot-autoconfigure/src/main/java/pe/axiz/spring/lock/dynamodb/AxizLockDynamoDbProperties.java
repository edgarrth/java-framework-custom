package pe.axiz.spring.lock.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** dynamodb settings retain their public configuration keys. */
@ConfigurationProperties("axiz.lock")
public record AxizLockDynamoDbProperties(@DefaultValue("axiz-locks") String table) {
  public AxizLockDynamoDbProperties {
    if (table == null || table.isBlank())
      throw new IllegalArgumentException("axiz.lock.table must not be blank");
  }
}
