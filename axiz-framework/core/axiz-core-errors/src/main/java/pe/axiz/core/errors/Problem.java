package pe.axiz.core.errors;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
}
