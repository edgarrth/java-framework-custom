package pe.axiz.spring.inbox;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Consumo idempotente ({@code axiz.inbox.*}): ids de mensajes procesados por consumidor.
 *
 * @param store {@code jdbc} (Aurora), {@code r2dbc} (Aurora desde un servicio reactivo) o {@code
 *     dynamodb}
 * @param lease tiempo durante el cual una entrega posee un mensaje antes de que una reentrega pueda
 *     tomarlo
 * @param retention tiempo durante el cual se recuerdan los ids procesados (TTL de DynamoDB)
 * @param purgeInterval frecuencia con la que se eliminan las filas JDBC o R2DBC expiradas
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
