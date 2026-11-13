package pe.axiz.spring.security;

import java.net.URL;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import pe.axiz.core.security.IssuerPolicy;

/** Accepts a token only when one of its audiences is configured for its issuer. */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error INVALID =
      new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token audience is not accepted", null);

  private final IssuerPolicy policy;

  public AudienceValidator(IssuerPolicy policy) {
    this.policy = Objects.requireNonNull(policy);
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    URL issuerUrl = jwt.getIssuer();
    String issuer = issuerUrl != null ? issuerUrl.toString() : "";
    List<String> audience = jwt.getAudience();
    List<String> aud = audience != null ? audience : List.of();
    return policy.audienceAccepted(issuer, aud)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(INVALID);
  }
}
