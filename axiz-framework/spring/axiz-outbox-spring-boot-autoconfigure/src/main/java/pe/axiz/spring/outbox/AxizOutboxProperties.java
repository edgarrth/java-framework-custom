package pe.axiz.spring.outbox;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Política compartida del relay; la configuración de persistencia pertenece a la integración
 * seleccionada.
 */
@ConfigurationProperties("axiz.outbox")
public record AxizOutboxProperties(@DefaultValue("jdbc") String store, @DefaultValue Relay relay) {
  /**
   * Relay hacia el broker (al menos una vez; seguro con varias réplicas gracias a {@code SKIP
   * LOCKED}).
   *
   * @param enabled indica si esta instancia hace relay
   * @param pollInterval espera entre sondeos cuando el outbox está vacío
   * @param batchSize registros por transacción
   * @param maxAttempts intentos antes de marcar un registro como fallido
   * @param initialDelay retraso del primer reintento
   * @param maxDelay tope del retraso de reintento
   * @param retention tiempo durante el cual se conservan los registros publicados
   * @param purgeInterval frecuencia con la que se purgan los registros publicados
   */
  public record Relay(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("1s") Duration pollInterval,
      @DefaultValue("100") int batchSize,
      @DefaultValue("10") int maxAttempts,
      @DefaultValue("1s") Duration initialDelay,
      @DefaultValue("10m") Duration maxDelay,
      @DefaultValue("7d") Duration retention,
      @DefaultValue("1h") Duration purgeInterval,
      @DefaultValue("3m") Duration leaseDuration,
      @DefaultValue("2s") Duration claimAllowance,
      @Nullable Duration publishBudget,
      @DefaultValue("15s") Duration shutdownGrace) {
    @ConstructorBinding
    public Relay {
      if (shutdownGrace.isNegative() || shutdownGrace.compareTo(Duration.ofMinutes(5)) > 0)
        throw new IllegalArgumentException(
            "outbox shutdown grace must be between zero and five minutes");
      if (leaseDuration.toNanos() <= 0)
        throw new IllegalArgumentException("outbox lease must be positive");
      if (claimAllowance.toNanos() <= 0 || (publishBudget != null && publishBudget.toNanos() <= 0))
        throw new IllegalArgumentException(
            "outbox claim-allowance and publish-budget must be positive");
    }

    /** Construcción con nombre; build comprueba los valores obligatorios y las invariantes. */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Estado mutable de construcción; se usa un builder por operación, nunca como estado
     * compartido.
     */
    public static final class Builder {
      private boolean enabled = false;
      private @Nullable Duration pollInterval = null;
      private int batchSize = 0;
      private int maxAttempts = 0;
      private @Nullable Duration initialDelay = null;
      private @Nullable Duration maxDelay = null;
      private @Nullable Duration retention = null;
      private @Nullable Duration purgeInterval = null;
      private @Nullable Duration leaseDuration = null;
      private @Nullable Duration claimAllowance = null;
      private @Nullable Duration publishBudget = null;
      private Duration shutdownGrace = Duration.ofSeconds(15);

      private Builder() {}

      public Builder enabled(boolean value) {
        this.enabled = value;
        return this;
      }

      public Builder pollInterval(Duration value) {
        this.pollInterval = value;
        return this;
      }

      public Builder batchSize(int value) {
        this.batchSize = value;
        return this;
      }

      public Builder maxAttempts(int value) {
        this.maxAttempts = value;
        return this;
      }

      public Builder initialDelay(Duration value) {
        this.initialDelay = value;
        return this;
      }

      public Builder maxDelay(Duration value) {
        this.maxDelay = value;
        return this;
      }

      public Builder retention(Duration value) {
        this.retention = value;
        return this;
      }

      public Builder purgeInterval(Duration value) {
        this.purgeInterval = value;
        return this;
      }

      public Builder leaseDuration(Duration value) {
        this.leaseDuration = value;
        return this;
      }

      public Builder claimAllowance(Duration value) {
        this.claimAllowance = value;
        return this;
      }

      public Builder publishBudget(@Nullable Duration value) {
        this.publishBudget = value;
        return this;
      }

      public Builder shutdownGrace(Duration value) {
        this.shutdownGrace = value;
        return this;
      }

      public Relay build() {
        return new Relay(
            enabled,
            Objects.requireNonNull(pollInterval, "pollInterval"),
            batchSize,
            maxAttempts,
            Objects.requireNonNull(initialDelay, "initialDelay"),
            Objects.requireNonNull(maxDelay, "maxDelay"),
            Objects.requireNonNull(retention, "retention"),
            Objects.requireNonNull(purgeInterval, "purgeInterval"),
            Objects.requireNonNull(leaseDuration, "leaseDuration"),
            Objects.requireNonNull(claimAllowance, "claimAllowance"),
            publishBudget,
            shutdownGrace);
      }
    }
  }
}
