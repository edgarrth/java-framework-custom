package pe.axiz.core.tokenization;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Objects;

/**
 * Resultado público inmutable; no contiene valor de origen, texto cifrado ni índice de igualdad.
 */
public record TokenizationResult(
    Token token, TokenType type, boolean created, Map<String, String> metadata) {
  public TokenizationResult {
    Objects.requireNonNull(token);
    Objects.requireNonNull(type);
    metadata = TokenMetadata.snapshot(metadata);
  }

  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "TokenMetadata.snapshot returns Map.copyOf with immutable String keys and values.")
  public Map<String, String> metadata() {
    return metadata;
  }

  @Override
  public String toString() {
    return "TokenizationResult[" + token.masked() + ", " + type.value() + "]";
  }
}
