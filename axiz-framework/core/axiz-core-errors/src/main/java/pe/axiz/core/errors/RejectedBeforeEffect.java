package pe.axiz.core.errors;

import java.util.Map;

/**
 * Se usa solo antes de cualquier escritura de negocio o efecto externo, nunca como fallo de negocio
 * genérico.
 */
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
