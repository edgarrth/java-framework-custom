package pe.axiz.spring.messaging;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mensajería ({@code axiz.messaging.*}): sobres CloudEvents 1.0 sobre MSK, SQS y SNS.
 *
 * @param source {@code source} de CloudEvents; por defecto, {@code urn:axiz:<application>}
 * @param typePrefix prefijo de los tipos de evento, p. ej. {@code pe.axiz.payments.}
 * @param destinations nombre lógico del destino asociado a la dirección física ({@code
 *     kafka:<topic>}, URL de la cola SQS o ARN del topic SNS)
 * @param consumer configuración de consumo
 */
@ConfigurationProperties("axiz.messaging")
public record AxizMessagingProperties(
    @Nullable String source,
    @DefaultValue("") String typePrefix,
    @DefaultValue Map<String, String> destinations,
    @DefaultValue Consumer consumer,
    @DefaultValue Routes routes) {
  public AxizMessagingProperties {
    destinations = Collections.unmodifiableMap(new LinkedHashMap<>(destinations));
  }

  /** Dirección física de un destino lógico (o el propio valor cuando ya es físico). */
  public String resolve(String destination) {
    return destinations.getOrDefault(destination, destination);
  }

  /**
   * Consumo.
   *
   * @param name nombre del consumidor usado para la deduplicación; por defecto, el nombre de la
   *     aplicación
   * @param maxAttempts entregas antes de enviar a dead-letter
   * @param initialDelay retraso de la primera reentrega
   * @param maxDelay tope del retraso de reentrega
   * @param ignoreUnknownTypes indica si los eventos sin handler se confirman en lugar de enviarse a
   *     dead-letter
   * @param subscriptions tipos de evento que deben tener un handler (se comprueba en el arranque)
   * @param deferInterval espera entre reentregas de un mensaje que otra entrega está procesando
   *     (lease del inbox vigente)
   * @param deferMaxWait tiempo durante el cual se sigue reentregando dicho mensaje antes de que
   *     siga la ruta de fallo; debe mantenerse por encima de {@code axiz.inbox.lease}
   */
  public record Consumer(
      @Nullable String name,
      @DefaultValue("5") int maxAttempts,
      @DefaultValue("1s") Duration initialDelay,
      @DefaultValue("5m") Duration maxDelay,
      @DefaultValue("false") boolean ignoreUnknownTypes,
      @DefaultValue List<String> subscriptions,
      @DefaultValue("10s") Duration deferInterval,
      @DefaultValue("10m") Duration deferMaxWait,
      @DefaultValue("true") boolean transactional,
      @DefaultValue("30s") Duration handlerTimeout) {
    public Consumer {
      subscriptions = List.copyOf(subscriptions);
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
      private @Nullable String name = null;
      private int maxAttempts = 0;
      private @Nullable Duration initialDelay = null;
      private @Nullable Duration maxDelay = null;
      private boolean ignoreUnknownTypes = false;
      private @Nullable List<String> subscriptions = List.of();
      private @Nullable Duration deferInterval = null;
      private @Nullable Duration deferMaxWait = null;
      private boolean transactional = false;
      private @Nullable Duration handlerTimeout = null;

      private Builder() {}

      public Builder name(@Nullable String value) {
        this.name = value;
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

      public Builder ignoreUnknownTypes(boolean value) {
        this.ignoreUnknownTypes = value;
        return this;
      }

      public Builder subscriptions(List<String> value) {
        this.subscriptions = List.copyOf(value);
        return this;
      }

      public Builder deferInterval(Duration value) {
        this.deferInterval = value;
        return this;
      }

      public Builder deferMaxWait(Duration value) {
        this.deferMaxWait = value;
        return this;
      }

      public Builder transactional(boolean value) {
        this.transactional = value;
        return this;
      }

      public Builder handlerTimeout(Duration value) {
        this.handlerTimeout = value;
        return this;
      }

      public Consumer build() {
        return new Consumer(
            name,
            maxAttempts,
            Objects.requireNonNull(initialDelay, "initialDelay"),
            Objects.requireNonNull(maxDelay, "maxDelay"),
            ignoreUnknownTypes,
            Objects.requireNonNull(subscriptions, "subscriptions"),
            Objects.requireNonNull(deferInterval, "deferInterval"),
            Objects.requireNonNull(deferMaxWait, "deferMaxWait"),
            transactional,
            Objects.requireNonNull(handlerTimeout, "handlerTimeout"));
      }
    }
  }

  /** Enrutamiento de eventos salientes, independiente de la estructura del servicio. */
  public record Routes(
      @DefaultValue("domain-events") String defaultDestination,
      @DefaultValue Map<String, String> byType) {
    public Routes {
      byType = Map.copyOf(byType);
    }
  }
}
