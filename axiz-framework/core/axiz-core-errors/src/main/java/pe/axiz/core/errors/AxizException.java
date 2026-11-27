package pe.axiz.core.errors;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Excepción base del framework. Lleva una definición de error y parámetros para el detalle
 * localizado. {@link #getMessage()} es solo para logs y nunca se envía a los clientes.
 */
public class AxizException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorDefinition definition;
  private final transient Map<String, Object> parameters;
  private final List<FieldViolation> violations;
  private final @Nullable ExternalErrorDetail external;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Exception extension point validates error metadata and takes defensive copies; no "
              + "external resource or finalizer is involved.")
  public AxizException(ErrorDefinition definition, String logMessage) {
    this(definition, logMessage, Map.of(), List.of(), null, null);
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Exception extension point validates error metadata and takes defensive copies; no "
              + "external resource or finalizer is involved.")
  public AxizException(ErrorDefinition definition, String logMessage, @Nullable Throwable cause) {
    this(definition, logMessage, Map.of(), List.of(), null, cause);
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Exception extension point validates error metadata and takes defensive copies; no "
              + "external resource or finalizer is involved.")
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

  /** Parámetros usados para generar el detalle localizado, por ejemplo {@code {limit=5000}}. */
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
