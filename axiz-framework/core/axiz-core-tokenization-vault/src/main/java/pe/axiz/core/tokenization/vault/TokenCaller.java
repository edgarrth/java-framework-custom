package pe.axiz.core.tokenization.vault;

import java.util.Set;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;

/** Verified identity; never construct from tokenization HTTP request fields. */
public record TokenCaller(
    String subject,
    String clientId,
    String tenant,
    Set<String> authorities,
    Set<TokenDomain> domains) {
  public TokenCaller {
    subject = VaultIdentifiers.identifier(subject, 128);
    clientId = VaultIdentifiers.identifier(clientId, 128);
    tenant = VaultIdentifiers.identifier(tenant, 128);
    if (authorities.isEmpty()
        || authorities.size() > 128
        || domains.isEmpty()
        || domains.size() > 64) throw TokenErrors.failure(TokenErrors.INVALID);
    authorities.forEach(authority -> VaultIdentifiers.identifier(authority, 128));
    authorities = Set.copyOf(authorities);
    domains = Set.copyOf(domains);
  }

  @Override
  public String toString() {
    return "TokenCaller[verified]";
  }
}
