package pe.axiz.core.tokenization.vault;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Límites explícitos propiedad del servidor; sin valores por defecto permisivos ni dimensiones de
 * presupuesto suministradas por la petición.
 */
public record TokenBudgetPolicy(
    Limits query,
    Limits reveal,
    Limits metadata,
    Limits writes,
    int subjectConcurrent,
    int clientConcurrent,
    int tenantConcurrent,
    Duration concurrencyRetryAfter) {
  /**
   * Cada dimensión tiene ventanas independientes de ráfaga de un segundo, de un minuto y de 24
   * horas.
   */
  public record Windows(long burst, long minute, long day) {
    public Windows {
      if (burst < 1 || minute < burst || day < minute || day > 1_000_000_000L)
        throw new IllegalArgumentException("Invalid token budget windows");
    }
  }

  /**
   * Un subject no puede reiniciar su presupuesto cambiando de cliente; el cliente tiene su propio
   * agregado.
   */
  public record Limits(Windows subject, Windows client, Windows tenant) {
    public Limits {
      Objects.requireNonNull(subject);
      Objects.requireNonNull(client);
      Objects.requireNonNull(tenant);
    }
  }

  public TokenBudgetPolicy {
    Objects.requireNonNull(query);
    Objects.requireNonNull(reveal);
    Objects.requireNonNull(metadata);
    Objects.requireNonNull(writes);
    Objects.requireNonNull(concurrencyRetryAfter);
    if (subjectConcurrent < 1
        || clientConcurrent < 1
        || tenantConcurrent < 1
        || subjectConcurrent > 1_000_000
        || clientConcurrent > 1_000_000
        || tenantConcurrent > 1_000_000
        || concurrencyRetryAfter.compareTo(Duration.ofSeconds(1)) < 0
        || concurrencyRetryAfter.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Invalid token concurrency policy");
  }

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable Limits query = null;
    private @Nullable Limits reveal = null;
    private @Nullable Limits metadata = null;
    private @Nullable Limits writes = null;
    private int subjectConcurrent = 0;
    private int clientConcurrent = 0;
    private int tenantConcurrent = 0;
    private @Nullable Duration concurrencyRetryAfter = null;

    private Builder() {}

    public Builder query(Limits value) {
      this.query = value;
      return this;
    }

    public Builder reveal(Limits value) {
      this.reveal = value;
      return this;
    }

    public Builder metadata(Limits value) {
      this.metadata = value;
      return this;
    }

    public Builder writes(Limits value) {
      this.writes = value;
      return this;
    }

    public Builder subjectConcurrent(int value) {
      this.subjectConcurrent = value;
      return this;
    }

    public Builder clientConcurrent(int value) {
      this.clientConcurrent = value;
      return this;
    }

    public Builder tenantConcurrent(int value) {
      this.tenantConcurrent = value;
      return this;
    }

    public Builder concurrencyRetryAfter(Duration value) {
      this.concurrencyRetryAfter = value;
      return this;
    }

    public TokenBudgetPolicy build() {
      return new TokenBudgetPolicy(
          Objects.requireNonNull(query, "query"),
          Objects.requireNonNull(reveal, "reveal"),
          Objects.requireNonNull(metadata, "metadata"),
          Objects.requireNonNull(writes, "writes"),
          subjectConcurrent,
          clientConcurrent,
          tenantConcurrent,
          Objects.requireNonNull(concurrencyRetryAfter, "concurrencyRetryAfter"));
    }
  }
}
