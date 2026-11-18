package pe.axiz.core.tokenization;

/** Explicit provider promises; operation UUID correlation is not durable idempotency. */
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
