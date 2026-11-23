package pe.axiz.quality.archunit;

/**
 * Frontera operativa de la tokenización. Los objetos de valor de token por sí solos no otorgan
 * acceso a la bóveda.
 */
public enum TokenizationScope {
  NONE,
  CLIENT,
  VAULT
}
