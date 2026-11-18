package pe.axiz.quality.archunit;

/** Operational tokenization boundary. Token value objects alone do not grant vault access. */
public enum TokenizationScope {
  NONE,
  CLIENT,
  VAULT
}
