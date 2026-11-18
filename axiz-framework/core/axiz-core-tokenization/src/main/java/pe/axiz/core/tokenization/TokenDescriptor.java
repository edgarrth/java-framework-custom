package pe.axiz.core.tokenization;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Objects;

/** Public immutable result; contains neither source value, ciphertext nor equality index. */
public record TokenDescriptor(Token token, TokenType type, Map<String, String> metadata) {
  public TokenDescriptor {
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
    return "TokenDescriptor[" + token.masked() + ", " + type.value() + "]";
  }
}
