package pe.axiz.spring.tokenization.vault.redis;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy;

/**
 * Espacio de nombres compartido explícito, todos los presupuestos y una conexión Redis dedicada y
 * autenticada.
 */
@ConfigurationProperties("axiz.tokenization.vault.redis")
public record AxizTokenizationRedisProperties(
    String namespace,
    TokenBudgetPolicy policy,
    Redis connection,
    @DefaultValue("false") boolean failOpen) {
  public AxizTokenizationRedisProperties {
    if (namespace == null || !namespace.matches("[a-z][a-z0-9.-]{0,63}"))
      throw new IllegalArgumentException("Explicit token budget namespace is required");
    Objects.requireNonNull(policy, "Explicit token budget policy is required");
    Objects.requireNonNull(connection, "Explicit token budget connection is required");
    if (failOpen) throw new IllegalArgumentException("Token budget fail-open is prohibited");
  }

  /**
   * Transporte standalone con TLS verificado, una referencia a secreto y comandos nativos acotados.
   */
  public record Redis(
      String host,
      @DefaultValue("6379") int port,
      @DefaultValue("true") boolean tls,
      String username,
      String passwordSecret,
      @DefaultValue("500ms") Duration timeout,
      @DefaultValue("1000") int queueCapacity) {
    public Redis {
      if (host == null
          || host.length() > 253
          || !host.matches("[A-Za-z0-9:.\\-]+")
          || port < 1
          || port > 65535
          || username == null
          || !username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
          || passwordSecret == null
          || !passwordSecret.matches("[A-Za-z0-9_./:-]{1,256}"))
        throw new IllegalArgumentException("Invalid dedicated token budget connection");
      if (timeout == null
          || timeout.compareTo(Duration.ofMillis(1)) < 0
          || timeout.compareTo(Duration.ofSeconds(3)) > 0
          || queueCapacity < 1
          || queueCapacity > 10000)
        throw new IllegalArgumentException(
            "Token budget transport must have finite deadlines and queues");
    }
  }
}
