package pe.axiz.core.tokenization.vault;

import pe.axiz.core.tokenization.Token;

/** Must generate independently random 128-bit tokens; insertion still checks for collision. */
@FunctionalInterface
public interface TokenGenerator {
  Token next();
}
