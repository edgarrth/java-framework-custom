package pe.axiz.spring.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;

/**
 * Transactional outbox ({@code axiz.outbox.*}) on Aurora PostgreSQL, over JDBC or R2DBC.
 *
 * @param table outbox table
 * @param initializeSchema when the table is created by the starter
 * @param relay relay settings
 * @param statementTimeout bound of each outbox statement run by the relay over R2DBC
 */
@ConfigurationProperties("axiz.outbox")
public record AxizOutboxProperties(
    @DefaultValue("axiz_outbox") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema,
    @DefaultValue Relay relay,
    @DefaultValue("30s") Duration statementTimeout) {

  /**
   * Relay to the broker (at-least-once; safe with several replicas thanks to {@code SKIP LOCKED}).
   *
   * @param enabled whether this instance relays
   * @param pollInterval wait between polls when the outbox is empty
   * @param batchSize records per transaction
   * @param maxAttempts attempts before a record is marked failed
   * @param initialDelay first retry delay
   * @param maxDelay retry delay cap
   * @param retention how long published records are kept
   * @param purgeInterval how often published records are purged
   */
  public record Relay(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("1s") Duration pollInterval,
      @DefaultValue("100") int batchSize,
      @DefaultValue("10") int maxAttempts,
      @DefaultValue("1s") Duration initialDelay,
      @DefaultValue("10m") Duration maxDelay,
      @DefaultValue("7d") Duration retention,
      @DefaultValue("1h") Duration purgeInterval) {}
}
