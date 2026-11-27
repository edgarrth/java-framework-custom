package pe.axiz.core.tokenization.vault;

import pe.axiz.core.tokenization.Token;

/**
 * Debe generar tokens aleatorios independientes de 128 bits; la inserción igualmente verifica
 * colisiones.
 */
@FunctionalInterface
public interface TokenGenerator {
  Token next();
}
