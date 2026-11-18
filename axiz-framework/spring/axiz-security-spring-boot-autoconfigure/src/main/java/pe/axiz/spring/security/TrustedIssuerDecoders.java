package pe.axiz.spring.security;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import pe.axiz.core.security.IssuerPolicy;
import pe.axiz.core.security.TrustedIssuer;
import reactor.core.publisher.Mono;

/**
 * One decoder per trusted issuer, created lazily and reused. Keys come from the configured JWKS URI
 * (cached by the decoder); unknown issuers get no decoder at all, so a forged {@code iss} never
 * triggers a network call.
 */
public final class TrustedIssuerDecoders implements AutoCloseable {

  private final IssuerHttpTransport transport;
  private final IssuerMetadataResolver metadata;
  private final IssuerPolicy policy;
  private final List<SignatureAlgorithm> algorithms;
  private final Map<String, JwtAccessProfile> profiles;
  private final Duration clockSkew;
  private final Map<String, JwtDecoder> servlet = new ConcurrentHashMap<>();
  private final Map<String, ReactiveJwtDecoder> reactive = new ConcurrentHashMap<>();

  public TrustedIssuerDecoders(IssuerPolicy policy, List<String> algorithms, Duration clockSkew) {
    this(
        policy,
        algorithms,
        clockSkew,
        new AxizSecurityProperties.Jwks(Duration.ofSeconds(2), Duration.ofSeconds(3)),
        false);
  }

  public TrustedIssuerDecoders(
      IssuerPolicy policy,
      List<String> algorithms,
      Duration clockSkew,
      AxizSecurityProperties.Jwks jwks,
      boolean allowHttp) {
    this(policy, algorithms, clockSkew, jwks, allowHttp, Map.of());
  }

  public TrustedIssuerDecoders(
      IssuerPolicy policy,
      List<String> algorithms,
      Duration clockSkew,
      AxizSecurityProperties.Jwks jwks,
      boolean allowHttp,
      Map<String, JwtAccessProfile> profiles) {
    this.policy = Objects.requireNonNull(policy);
    this.profiles = Map.copyOf(profiles);
    if (profiles.keySet().stream().anyMatch(issuer -> !policy.isTrusted(issuer)))
      throw new IllegalArgumentException("Access-token profiles require a trusted issuer");
    this.algorithms =
        algorithms.stream()
            .map(
                a ->
                    Objects.requireNonNull(
                        SignatureAlgorithm.from(a), "Unsupported algorithm " + a))
            .toList();
    this.clockSkew = Objects.requireNonNull(clockSkew);
    if (this.algorithms.isEmpty() || policy.all().size() > 128)
      throw new IllegalArgumentException(
          "A bounded issuer policy and signature algorithms are required");
    for (TrustedIssuer issuer : policy.all()) {
      if (algorithms(issuer).isEmpty())
        throw new IllegalArgumentException(
            "The strict access-token profile requires an algorithm other than RS256");
    }
    transport = new IssuerHttpTransport(jwks, allowHttp);
    try {
      for (TrustedIssuer issuer : policy.all()) {
        URI keys = issuer.jwkSetUri();
        transport.validate(keys != null ? keys : URI.create(issuer.issuer()));
      }
    } catch (RuntimeException invalid) {
      transport.close();
      throw invalid;
    }
    metadata = new IssuerMetadataResolver(transport);
  }

  public @Nullable JwtDecoder servlet(String issuer) {
    return policy
        .find(issuer)
        .map(t -> servlet.computeIfAbsent(issuer, i -> build(t)))
        .orElse(null);
  }

  public @Nullable ReactiveJwtDecoder reactive(String issuer) {
    return policy
        .find(issuer)
        .map(t -> reactive.computeIfAbsent(issuer, i -> buildReactive(t)))
        .orElse(null);
  }

  public IssuerPolicy policy() {
    return policy;
  }

  /**
   * Checks of every access token: an expiration is required ({@code exp}, as in the JWT profile for
   * OAuth 2.0 access tokens, RFC 9068), it and {@code nbf} are checked with the clock skew, the
   * issuer must be the trusted one and the audience one of its audiences. The signature algorithm
   * is restricted by the decoder.
   */
  OAuth2TokenValidator<Jwt> validator(TrustedIssuer issuer) {
    return new DelegatingOAuth2TokenValidator<>(
        new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
        new JwtTimestampValidator(clockSkew),
        new JwtIssuerValidator(issuer.issuer()),
        new AudienceValidator(policy),
        profiles.getOrDefault(issuer.issuer(), JwtAccessProfile.COMPATIBLE).validator());
  }

  private List<SignatureAlgorithm> algorithms(TrustedIssuer issuer) {
    if (profiles.getOrDefault(issuer.issuer(), JwtAccessProfile.COMPATIBLE)
        != JwtAccessProfile.STRICT) return algorithms;
    return algorithms.stream().filter(value -> value != SignatureAlgorithm.RS256).toList();
  }

  private JwtDecoder build(TrustedIssuer issuer) {
    var cached = new AtomicReference<@Nullable NimbusJwtDecoder>();
    return token -> {
      var decoder = cached.get();
      if (decoder == null) {
        var built = buildServlet(issuer, metadata.jwksUri(issuer));
        cached.compareAndSet(null, built);
        decoder = Objects.requireNonNull(cached.get());
      }
      try {
        return decoder.decode(token);
      } catch (RuntimeException failure) {
        var unavailable = IssuerUnavailableException.find(failure);
        if (unavailable != null) throw unavailable;
        throw failure;
      }
    };
  }

  private NimbusJwtDecoder buildServlet(TrustedIssuer issuer, URI jwks) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwks.toString())
            // Type validation is supplied by the issuer's explicit disjoint profile validator.
            .validateType(false)
            .restOperations(transport.rest(jwks))
            .jwsAlgorithms(
                set -> {
                  set.clear();
                  set.addAll(algorithms(issuer));
                })
            .build();
    decoder.setJwtValidator(validator(issuer));
    return decoder;
  }

  private ReactiveJwtDecoder buildReactive(TrustedIssuer issuer) {
    var cached = new AtomicReference<@Nullable NimbusReactiveJwtDecoder>();
    return token ->
        Mono.defer(
                () -> {
                  var decoder = cached.get();
                  if (decoder != null) return decoder.decode(token);
                  return metadata
                      .reactiveJwksUri(issuer)
                      .flatMap(
                          jwks -> {
                            var built =
                                NimbusReactiveJwtDecoder.withJwkSetUri(jwks.toString())
                                    .validateType(false)
                                    .webClient(transport.web(jwks))
                                    .jwsAlgorithms(
                                        set -> {
                                          set.clear();
                                          set.addAll(algorithms(issuer));
                                        })
                                    .build();
                            built.setJwtValidator(validator(issuer));
                            cached.compareAndSet(null, built);
                            return Objects.requireNonNull(cached.get()).decode(token);
                          });
                })
            .onErrorMap(
                failure -> {
                  var unavailable = IssuerUnavailableException.find(failure);
                  return unavailable != null ? unavailable : failure;
                });
  }

  /** Non-blocking startup: retries failed discovery in owned virtual threads until closed. */
  public void warmUp() {
    metadata.warmUp(policy.all());
  }

  @Override
  public void close() {
    metadata.close();
    transport.close();
  }
}
