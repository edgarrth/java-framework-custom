package pe.axiz.core.errors;

import java.util.Map;

/** Use only before any business write or external effect, never as a generic business failure. */
public final class RejectedBeforeEffect extends BusinessException implements NoEffect {
  private static final long serialVersionUID = 1L;

  public RejectedBeforeEffect(ErrorDefinition definition, String message) {
    super(definition, message);
  }

  public RejectedBeforeEffect(
      ErrorDefinition definition, String message, Map<String, ?> parameters) {
    super(definition, message, parameters);
  }
}
