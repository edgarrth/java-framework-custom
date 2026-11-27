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
import pe.axiz.core.security.RateLimitKeys;
import pe.axiz.core.security.RateLimitUnavailableException;
import pe.axiz.core.security.RateLimiter;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Límite de tasa reactivo; el limitador usa E/S asíncrona nativa. */
public final class RateLimitWebFilter implements WebFilter {

  private final AsyncRateLimiter limiter;
  private final RateLimitRules rules;
  private final SecurityProblems problems;
  private final Scheduler scheduler;

  public RateLimitWebFilter(
      AsyncRateLimiter limiter,
      RateLimitRules rules,
      SecurityProblems problems,
      Scheduler scheduler) {
    this.scheduler = Objects.requireNonNull(scheduler);
    this.limiter = Objects.requireNonNull(limiter);
    this.rules = Objects.requireNonNull(rules);
    this.problems = Objects.requireNonNull(problems);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(AxizAuthentication.class::isInstance)
        .map(a -> Optional.of((AxizAuthentication) a))
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            auth -> {
              Optional<AxizSecurityProperties.Rule> matched =
                  rules.match(
                      exchange.getRequest().getPath().pathWithinApplication().value(),
                      auth.isEmpty());
              if (matched.isEmpty()) return chain.filter(exchange);
              AxizSecurityProperties.Rule rule = matched.get();
              String key;
              if (auth.isPresent()) {
                key = RateLimitRules.key(rule, auth.get().axizPrincipal());
              } else {
                var remote = exchange.getRequest().getRemoteAddress();
                if (remote == null || remote.getAddress() == null) return unavailable(exchange);
                key = RateLimitKeys.forAnonymous(remote.getAddress().getHostAddress(), rule.name());
              }
              return ReactiveStages.from(
                      () ->
                          limiter.tryAcquire(
                              key, rule.limit(), rule.window(), rule.failurePolicy()))
                  .onErrorResume(
                      RateLimitUnavailableException.class,
                      failure -> unavailable(exchange).then(Mono.empty()))
                  .flatMap(decision -> decide(exchange, chain, decision));
            });
  }

  private Mono<Void> unavailable(ServerWebExchange exchange) {
    return ReactiveProblemHandlers.write(
        scheduler,
        exchange,
        HttpStatus.SERVICE_UNAVAILABLE,
        () ->
            problems.unavailable(
                ReactiveProblemHandlers.locale(exchange), exchange.getRequest().getPath().value()));
  }

  private Mono<Void> decide(
      ServerWebExchange exchange, WebFilterChain chain, RateLimiter.Decision d) {
    if (!d.allowed()) {
      exchange
          .getResponse()
          .getHeaders()
          .set("Retry-After", Long.toString(Math.max(1, d.retryAfter().toSeconds())));
      return ReactiveProblemHandlers.write(
          scheduler,
          exchange,
          HttpStatus.TOO_MANY_REQUESTS,
          () ->
              problems.tooManyRequests(
                  ReactiveProblemHandlers.locale(exchange),
                  exchange.getRequest().getPath().value()));
    }
    exchange.getResponse().getHeaders().set("RateLimit-Remaining", Long.toString(d.remaining()));
    return chain.filter(exchange);
  }
}
