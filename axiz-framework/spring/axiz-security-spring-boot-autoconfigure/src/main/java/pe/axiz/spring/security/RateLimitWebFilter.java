package pe.axiz.spring.security;

import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import pe.axiz.core.security.AsyncRateLimiter;
import pe.axiz.core.security.RateLimitUnavailableException;
import pe.axiz.core.security.RateLimiter;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/** Reactive rate limit; the limiter uses native asynchronous I/O. */
public final class RateLimitWebFilter implements WebFilter {

  private final AsyncRateLimiter limiter;
  private final RateLimitRules rules;
  private final SecurityProblems problems;

  public RateLimitWebFilter(
      AsyncRateLimiter limiter, RateLimitRules rules, SecurityProblems problems) {
    this.limiter = Objects.requireNonNull(limiter);
    this.rules = Objects.requireNonNull(rules);
    this.problems = Objects.requireNonNull(problems);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    Optional<AxizSecurityProperties.Rule> rule =
        rules.match(exchange.getRequest().getPath().pathWithinApplication().value());
    if (rule.isEmpty()) {
      return chain.filter(exchange);
    }
    AxizSecurityProperties.Rule r = rule.get();
    return ReactiveSecurityContextHolder.getContext()
        // a context without authentication (anonymous) is empty here, not a null element
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(AxizAuthentication.class::isInstance)
        .map(a -> Optional.of(RateLimitRules.key(r, ((AxizAuthentication) a).axizPrincipal())))
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            key ->
                key.isEmpty()
                    ? chain.filter(exchange)
                    : ReactiveStages.from(
                            () ->
                                limiter.tryAcquire(
                                    key.get(), r.limit(), r.window(), r.failurePolicy()))
                        .onErrorResume(
                            RateLimitUnavailableException.class,
                            failure ->
                                ReactiveProblemHandlers.write(
                                        exchange,
                                        HttpStatus.SERVICE_UNAVAILABLE,
                                        problems.unavailable(
                                            ReactiveProblemHandlers.locale(exchange),
                                            exchange.getRequest().getPath().value()))
                                    .then(Mono.empty()))
                        .flatMap(d -> decide(exchange, chain, d)));
  }

  private Mono<Void> decide(
      ServerWebExchange exchange, WebFilterChain chain, RateLimiter.Decision d) {
    if (!d.allowed()) {
      exchange
          .getResponse()
          .getHeaders()
          .set("Retry-After", Long.toString(Math.max(1, d.retryAfter().toSeconds())));
      return ReactiveProblemHandlers.write(
          exchange,
          HttpStatus.TOO_MANY_REQUESTS,
          problems.tooManyRequests(
              ReactiveProblemHandlers.locale(exchange), exchange.getRequest().getPath().value()));
    }
    exchange.getResponse().getHeaders().set("RateLimit-Remaining", Long.toString(d.remaining()));
    return chain.filter(exchange);
  }
}
