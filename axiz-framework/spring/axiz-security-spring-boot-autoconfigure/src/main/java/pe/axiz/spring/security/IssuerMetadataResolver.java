package pe.axiz.spring.security;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.security.TrustedIssuer;
import reactor.core.publisher.Mono;

/** Exact-issuer discovery, sharing bounded transport and per-endpoint failure backoff. */
final class IssuerMetadataResolver implements AutoCloseable {
  private final IssuerHttpTransport transport;
  private final Map<String, URI> resolved = new ConcurrentHashMap<>();
  private final List<Thread> warmers = new CopyOnWriteArrayList<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();

  IssuerMetadataResolver(IssuerHttpTransport transport) {
    this.transport = transport;
  }

  URI jwksUri(TrustedIssuer issuer) {
    URI known = known(issuer);
    if (known != null) return known;
    transport.blocking(transport.get(metadata(issuer), body -> accept(issuer, body)));
    return Objects.requireNonNull(resolved.get(issuer.issuer()));
  }

  Mono<URI> reactiveJwksUri(TrustedIssuer issuer) {
    return Mono.defer(
        () -> {
          URI known = known(issuer);
          if (known != null) return Mono.just(known);
          return Mono.fromFuture(transport.get(metadata(issuer), body -> accept(issuer, body)))
              .map(ignored -> Objects.requireNonNull(resolved.get(issuer.issuer())));
        });
  }

  private @Nullable URI known(TrustedIssuer issuer) {
    return issuer.jwkSetUri() != null ? issuer.jwkSetUri() : resolved.get(issuer.issuer());
  }

  private static URI metadata(TrustedIssuer issuer) {
    return URI.create(issuer.issuer().replaceAll("/+$", "") + "/.well-known/openid-configuration");
  }

  private void accept(TrustedIssuer issuer, Map<String, Object> body) {
    if (!issuer.issuer().equals(body.get("issuer"))
        || !(body.get("jwks_uri") instanceof String keys))
      throw new IllegalArgumentException("Issuer metadata mismatch");
    URI uri = URI.create(keys);
    transport.validate(uri);
    resolved.put(issuer.issuer(), uri);
  }

  void warmUp(List<TrustedIssuer> issuers) {
    if (closed.get() || !started.compareAndSet(false, true)) return;
    for (TrustedIssuer issuer : issuers) {
      if (!issuer.discovered()) continue;
      Thread thread =
          Thread.ofVirtual()
              .name("axiz-issuer-metadata")
              .unstarted(
                  () -> {
                    while (!closed.get()) {
                      try {
                        jwksUri(issuer);
                        return;
                      } catch (IssuerUnavailableException unavailable) {
                        try {
                          Thread.sleep(Duration.ofSeconds(unavailable.retryAfterSeconds()));
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          return;
                        }
                      }
                    }
                  });
      warmers.add(thread);
      thread.start();
    }
  }

  @Override
  public void close() {
    closed.set(true);
    warmers.forEach(Thread::interrupt);
    warmers.clear();
  }
}
