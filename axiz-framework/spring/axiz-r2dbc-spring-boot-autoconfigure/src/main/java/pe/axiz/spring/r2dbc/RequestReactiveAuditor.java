package pe.axiz.spring.r2dbc;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.ReactiveAuditorAware;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import reactor.core.publisher.Mono;

/**
 * Auditor of {@code createdBy}/{@code updatedBy} for reactive code: the end user, or the calling
 * application for machine-to-machine requests, read from the request metadata that the web and
 * security starters put in the Reactor context (or the thread-local of a consumer thread); {@code
 * system} otherwise.
 */
public class RequestReactiveAuditor implements ReactiveAuditorAware<String> {

  @Override
  public Mono<String> getCurrentAuditor() {
    return Mono.deferContextual(
        ctx -> {
          RequestMetadata fromContext = ctx.getOrDefault(RequestContext.KEY, null);
          return Mono.just(auditor(fromContext != null ? fromContext : RequestContext.get()));
        });
  }

  static String auditor(@Nullable RequestMetadata m) {
    if (m == null) {
      return "system";
    }
    if (m.subject() != null) {
      return m.subject();
    }
    return m.clientId() != null ? m.clientId() : "system";
  }
}
