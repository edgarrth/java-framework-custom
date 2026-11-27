package pe.axiz.spring.r2dbc;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.ReactiveAuditorAware;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import reactor.core.publisher.Mono;

/**
 * Auditor de {@code createdBy}/{@code updatedBy} para código reactivo: el usuario final, o la
 * aplicación invocante en solicitudes de máquina a máquina, leído de los metadatos de la solicitud
 * que los starters web y de seguridad colocan en el contexto de Reactor (o el thread-local de un
 * hilo consumidor); {@code unattributed} en otro caso.
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
      return "unattributed";
    }
    String subject = m.subject();
    if (subject != null && !subject.isBlank()) {
      return subject;
    }
    String clientId = m.clientId();
    return clientId != null && !clientId.isBlank() ? clientId : "unattributed";
  }
}
