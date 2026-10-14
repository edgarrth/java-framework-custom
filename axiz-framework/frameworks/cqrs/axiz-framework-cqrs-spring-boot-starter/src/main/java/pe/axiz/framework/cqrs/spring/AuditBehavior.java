package pe.axiz.framework.cqrs.spring;

import java.util.Objects;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.errors.AxizException;
import pe.axiz.framework.cqrs.AuditedCommand;
import pe.axiz.framework.cqrs.Invocation;
import pe.axiz.framework.cqrs.PipelineBehavior;

/** Records {@link AuditedCommand} executions with their outcome (details are sanitized). */
public class AuditBehavior implements PipelineBehavior {

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
