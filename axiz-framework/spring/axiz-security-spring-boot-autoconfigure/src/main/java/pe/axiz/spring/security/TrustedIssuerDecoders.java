package pe.axiz.spring.security;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import pe.axiz.core.security.IssuerPolicy;
import pe.axiz.core.security.TrustedIssuer;

/**
 * One decoder per trusted issuer, created lazily and reused. Keys come from the configured JWKS URI
 * (cached by the decoder); unknown issuers get no decoder at all, so a forged {@code iss} never
 * triggers a network call.
 */
public class TrustedIssuerDecoders {

  private final IssuerPolicy policy;
  private final List<SignatureAlgorithm> algorithms;
  private final Duration clockSkew;
  private final Map<String, NimbusJwtDecoder> servlet = new ConcurrentHashMap<>();
  private final Map<String, NimbusReactiveJwtDecoder> reactive = new ConcurrentHashMap<>();

  public TrustedIssuerDecoders(IssuerPolicy policy, List<String> algorithms, Duration clockSkew) {
    this.policy = Objects.requireNonNull(policy);
    this.algorithms =
        algorithms.stream()
            .map(
                a ->
                    Objects.requireNonNull(
                        SignatureAlgorithm.from(a), "Unsupported algorithm " + a))
            .toList();
    this.clockSkew = Objects.requireNonNull(clockSkew);
  }

  public @Nullable NimbusJwtDecoder servlet(String issuer) {
    return policy
        .find(issuer)
        .map(t -> servlet.computeIfAbsent(issuer, i -> build(t)))
        .orElse(null);
  }

  public @Nullable NimbusReactiveJwtDecoder reactive(String issuer) {
    return policy
        .find(issuer)
        .map(t -> reactive.computeIfAbsent(issuer, i -> buildReactive(t)))
        .orElse(null);
  }

  public IssuerPolicy policy() {
    return policy;
  }

  OAuth2TokenValidator<Jwt> validator(TrustedIssuer issuer) {
    return new DelegatingOAuth2TokenValidator<>(
        new JwtTimestampValidator(clockSkew),
        new JwtIssuerValidator(issuer.issuer()),
        new AudienceValidator(policy));
  }

  private NimbusJwtDecoder build(TrustedIssuer issuer) {
    URI jwks = issuer.jwkSetUri();
    NimbusJwtDecoder decoder =
        (jwks == null
                ? NimbusJwtDecoder.withIssuerLocation(issuer.issuer())
                : NimbusJwtDecoder.withJwkSetUri(jwks.toString()))
            .jwsAlgorithms(set -> set.addAll(algorithms))
            .build();
    decoder.setJwtValidator(validator(issuer));
    return decoder;
  }

  private NimbusReactiveJwtDecoder buildReactive(TrustedIssuer issuer) {
    URI jwks = issuer.jwkSetUri();
    NimbusReactiveJwtDecoder decoder =
        (jwks == null
                ? NimbusReactiveJwtDecoder.withIssuerLocation(issuer.issuer())
                : NimbusReactiveJwtDecoder.withJwkSetUri(jwks.toString()))
            .jwsAlgorithms(set -> set.addAll(algorithms))
            .build();
    decoder.setJwtValidator(validator(issuer));
    return decoder;
  }
}
