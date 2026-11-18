package pe.axiz.spring.tokenization.vault.http;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Retains no-store even for Spring Security and framework-generated error responses. */
final class ReactiveVaultGuard implements WebFilter, Ordered {
  private final VaultHttpPolicy policy;

  ReactiveVaultGuard(VaultHttpPolicy policy) {
    this.policy = policy;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!policy.protectedPath(exchange.getRequest().getPath().pathWithinApplication().value()))
      return chain.filter(exchange);
    VaultHttpPolicy.noStore(exchange.getResponse().getHeaders());
    exchange
        .getResponse()
        .beforeCommit(
            () ->
                Mono.fromRunnable(
                    () -> VaultHttpPolicy.noStore(exchange.getResponse().getHeaders())));
    return Mono.defer(
        () -> {
          try {
            policy.transport("https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme()));
          } catch (RuntimeException failure) {
            return Mono.using(
                () -> VaultHttpReply.error(failure),
                reply -> ReactiveVaultController.write(exchange, reply),
                VaultHttpReply::close);
          }
          return chain.filter(exchange);
        });
  }
}
