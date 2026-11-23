package pe.axiz.quality.archunit;

/**
 * Manejo permitido de datos de tarjeta tipados; independiente del acceso genérico a la bóveda de
 * tokens.
 */
public enum CardDataScope {
  NONE,
  EDGE,
  VAULT
}
