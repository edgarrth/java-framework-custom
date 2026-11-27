package pe.axiz.core.errors;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Problem details RFC 9457 más los miembros de extensión de axiz ({@code code}, {@code
 * correlationId}, {@code timestamp}, {@code violations}, {@code external}).
 *
 * @param type URI del tipo de problema
 * @param title resumen breve
 * @param status estado HTTP
 * @param detail explicación localizada de esta ocurrencia (nunca el mensaje de una excepción
 *     interna)
 * @param instance URI de la petición que falló
 * @param code código de error de axiz
 * @param correlationId id de correlación del flujo
 * @param timestamp cuándo ocurrió
 * @param violations violaciones de campos, para errores de validación
 * @param external resumen público del fallo de una dependencia, solo cuando la exposición está
 *     habilitada
 */
public record Problem(
    String type,
    String title,
    int status,
    String detail,
    @Nullable String instance,
    String code,
    @Nullable String correlationId,
    Instant timestamp,
    List<FieldViolation> violations,
    @Nullable Map<String, Object> external) {

  public Problem {
    violations = List.copyOf(violations);
    external = external == null ? null : Map.copyOf(external);
  }

  public static final String MEDIA_TYPE = "application/problem+json";

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable String type = null;
    private @Nullable String title = null;
    private int status = 0;
    private @Nullable String detail = null;
    private @Nullable String instance = null;
    private @Nullable String code = null;
    private @Nullable String correlationId = null;
    private @Nullable Instant timestamp = null;
    private @Nullable List<FieldViolation> violations = List.of();
    private @Nullable Map<String, Object> external = null;

    private Builder() {}

    public Builder type(String value) {
      this.type = value;
      return this;
    }

    public Builder title(String value) {
      this.title = value;
      return this;
    }

    public Builder status(int value) {
      this.status = value;
      return this;
    }

    public Builder detail(String value) {
      this.detail = value;
      return this;
    }

    public Builder instance(@Nullable String value) {
      this.instance = value;
      return this;
    }

    public Builder code(String value) {
      this.code = value;
      return this;
    }

    public Builder correlationId(@Nullable String value) {
      this.correlationId = value;
      return this;
    }

    public Builder timestamp(Instant value) {
      this.timestamp = value;
      return this;
    }

    public Builder violations(List<FieldViolation> value) {
      this.violations = List.copyOf(value);
      return this;
    }

    public Builder external(@Nullable Map<String, Object> value) {
      this.external = value == null ? null : Map.copyOf(value);
      return this;
    }

    public Problem build() {
      return new Problem(
          Objects.requireNonNull(type, "type"),
          Objects.requireNonNull(title, "title"),
          status,
          Objects.requireNonNull(detail, "detail"),
          instance,
          Objects.requireNonNull(code, "code"),
          correlationId,
          Objects.requireNonNull(timestamp, "timestamp"),
          Objects.requireNonNull(violations, "violations"),
          external);
    }
  }
}
