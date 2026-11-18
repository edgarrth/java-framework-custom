package pe.axiz.core.errors;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * RFC 9457 problem details plus axiz extension members ({@code code}, {@code correlationId}, {@code
 * timestamp}, {@code violations}, {@code external}).
 *
 * @param type problem type URI
 * @param title short summary
 * @param status HTTP status
 * @param detail localized explanation for this occurrence (never an internal exception message)
 * @param instance URI of the request that failed
 * @param code axiz error code
 * @param correlationId correlation id of the flow
 * @param timestamp when it happened
 * @param violations field violations, for validation errors
 * @param external public summary of a dependency failure, only when exposure is enabled
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

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
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
