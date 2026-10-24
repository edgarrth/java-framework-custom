package pe.axiz.spring.security;

import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import pe.axiz.core.security.RateLimiter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Reactive rate limit; the limiter call runs off the event loop. */
public class RateLimitWebFilter implements WebFilter {

  private final RateLimiter limiter;
  private final RateLimitRules rules;
  private final SecurityProblems problems;
  private final boolean failOpen;

  public RateLimitWebFilter(RateLimiter limiter, RateLimitRules rules, SecurityProblems problems) {
    this(limiter, rules, problems, true);
  }

  /**
   * Creates the filter.
   *
   * @param failOpen policy when the limiter store is unavailable, for rules without their own
   */
  public RateLimitWebFilter(
      RateLimiter limiter, RateLimitRules rules, SecurityProblems problems, boolean failOpen) {
    this.limiter = Objects.requireNonNull(limiter);
    this.rules = Objects.requireNonNull(rules);
    this.problems = Objects.requireNonNull(problems);
    this.failOpen = failOpen;
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
                    : Mono.fromCallable(
                            () ->
                                limiter.tryAcquire(
                                    key.get(), r.limit(), r.window(), r.failOpen(failOpen)))
                        .subscribeOn(Schedulers.boundedElastic())
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
