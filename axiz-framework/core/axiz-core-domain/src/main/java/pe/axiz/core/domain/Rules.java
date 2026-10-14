package pe.axiz.core.domain;

import java.util.Map;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorDefinition;

/** Guard clauses for aggregates that raise business errors from the catalog. */
public final class Rules {

  private Rules() {}

  /**
   * Fails with the given business error when the condition is false.
   *
   * @param condition rule that must hold
   * @param error catalog definition to raise
   * @param logMessage message for logs (never shown to clients)
   */
  public static void check(boolean condition, ErrorDefinition error, String logMessage) {
    if (!condition) {
      throw new BusinessException(error, logMessage);
    }
  }

  public static void check(
      boolean condition, ErrorDefinition error, String logMessage, Map<String, ?> parameters) {
    if (!condition) {
      throw new BusinessException(error, logMessage, parameters);
    }
  }
}
