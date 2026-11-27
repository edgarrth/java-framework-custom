package pe.axiz.core.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lista de emisores confiables permitidos. Los tokens de cualquier otro emisor se rechazan antes de
 * la validación.
 */
public final class IssuerPolicy {

  private final Map<String, TrustedIssuer> issuers;

  public IssuerPolicy(Collection<TrustedIssuer> trusted) {
    Map<String, TrustedIssuer> map = new LinkedHashMap<>();
    for (TrustedIssuer t : trusted) {
      if (map.put(t.issuer(), t) != null) {
        throw new IllegalArgumentException("Duplicate issuer " + t.issuer());
      }
    }
    if (map.isEmpty()) {
      throw new IllegalArgumentException("At least one trusted issuer is required");
    }
    this.issuers = Map.copyOf(map);
  }

  public Optional<TrustedIssuer> find(String issuer) {
    return Optional.ofNullable(issuers.get(issuer));
  }

  public boolean isTrusted(String issuer) {
    return issuers.containsKey(issuer);
  }

  public List<TrustedIssuer> all() {
    return List.copyOf(issuers.values());
  }

  /** Indica si al menos una audiencia del token se acepta para el emisor. */
  public boolean audienceAccepted(String issuer, List<String> tokenAudiences) {
    return find(issuer)
        .map(t -> tokenAudiences.stream().anyMatch(t.audiences()::contains))
        .orElse(false);
  }
}
