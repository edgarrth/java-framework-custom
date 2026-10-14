package pe.axiz.core.errors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Base exception of the framework. It carries an error definition and parameters for the localized
 * detail. {@link #getMessage()} is for logs only and is never sent to clients.
 */
public class AxizException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorDefinition definition;
  private final transient Map<String, Object> parameters;
  private final List<FieldViolation> violations;
  private final @Nullable ExternalErrorDetail external;

  public AxizException(ErrorDefinition definition, String logMessage) {
    this(definition, logMessage, Map.of(), List.of(), null, null);
  }

  public AxizException(ErrorDefinition definition, String logMessage, @Nullable Throwable cause) {
    this(definition, logMessage, Map.of(), List.of(), null, cause);
  }

  protected AxizException(
      ErrorDefinition definition,
      String logMessage,
      Map<String, ?> parameters,
      List<FieldViolation> violations,
      @Nullable ExternalErrorDetail external,
      @Nullable Throwable cause) {
    super(logMessage, cause);
    this.definition = Objects.requireNonNull(definition, "definition");
    this.parameters = Map.copyOf(new LinkedHashMap<String, Object>(parameters));
    this.violations = List.copyOf(violations);
    this.external = external;
  }

  public ErrorDefinition definition() {
    return definition;
  }

  public ErrorCode code() {
    return definition.code();
  }

  public ErrorCategory category() {
    return definition.category();
  }

  public ErrorType type() {
    return definition.type();
  }

  /** Parameters used to render the localized detail, e.g. {@code {limit=5000}}. */
  public Map<String, Object> parameters() {
    return parameters;
  }

  public List<FieldViolation> violations() {
    return violations;
  }

  public @Nullable ExternalErrorDetail external() {
    return external;
  }
}
