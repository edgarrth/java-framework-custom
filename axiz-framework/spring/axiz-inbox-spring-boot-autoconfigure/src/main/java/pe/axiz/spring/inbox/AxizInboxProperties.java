package pe.axiz.spring.inbox;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Idempotent consumption ({@code axiz.inbox.*}): processed message ids per consumer.
 *
 * @param store {@code jdbc} (Aurora), {@code r2dbc} (Aurora from a reactive service) or {@code
 *     dynamodb}
 * @param lease how long a delivery owns a message before a redelivery may take over
 * @param retention how long processed ids are remembered (DynamoDB TTL)
 * @param purgeInterval how often expired JDBC or R2DBC rows are deleted
 */
@ConfigurationProperties("axiz.inbox")
public record AxizInboxProperties(
    @DefaultValue("jdbc") String store,
    @DefaultValue("5m") Duration lease,
    @DefaultValue("14d") Duration retention,
    @DefaultValue("1h") Duration purgeInterval,
    @DefaultValue("auto") String transactional) {
  public AxizInboxProperties {
    if (lease.isZero() || lease.isNegative() || retention.compareTo(lease) <= 0)
      throw new IllegalArgumentException("Inbox retention must exceed a positive processing lease");
    if (purgeInterval.isZero() || purgeInterval.isNegative())
      throw new IllegalArgumentException("Inbox purge interval must be positive");
    if (!Set.of("auto", "true", "false").contains(transactional))
      throw new IllegalArgumentException("axiz.inbox.transactional must be auto, true or false");
  }
}
