package pe.axiz.core.tokenization;

/**
 * Promesas explícitas del proveedor; la correlación por UUID de operación no es idempotencia
 * durable.
 */
public record TokenizerCapabilities(
    boolean deduplicates,
    boolean acceptsOperationId,
    boolean supportsLookup,
    boolean supportsDescribe,
    boolean supportsDelete,
    boolean supportsDetokenize) {
  public static TokenizerCapabilities vault() {
    return new TokenizerCapabilities(true, false, true, true, true, true);
  }
}
