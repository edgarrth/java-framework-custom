package pe.axiz.extension.cqrs.spring;

import java.util.Objects;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.errors.AxizException;
import pe.axiz.extension.cqrs.AuditedCommand;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;

/** Registra las ejecuciones de {@link AuditedCommand} con su resultado (los detalles se sanean). */
public final class AuditBehavior implements PipelineBehavior {

  private final AuditTrail trail;

  public AuditBehavior(AuditTrail trail) {
    this.trail = Objects.requireNonNull(trail);
  }

  @Override
  public int order() {
    return AUDIT;
  }

  @Override
  public <R> R handle(Invocation<R> invocation, Next<R> next) {
    if (!(invocation.request() instanceof AuditedCommand<?> command)) {
      return next.proceed();
    }
    AuditTrail.Builder record =
        trail
            .record(command.auditAction(), command.auditResource())
            .resourceId(command.auditResourceId())
            .detail("command", command);
    try {
      R result = next.proceed();
      record.success();
      return result;
    } catch (RuntimeException e) {
      record.failure(
          e instanceof AxizException a ? a.code().value() : e.getClass().getSimpleName());
      throw e;
    }
  }
}
