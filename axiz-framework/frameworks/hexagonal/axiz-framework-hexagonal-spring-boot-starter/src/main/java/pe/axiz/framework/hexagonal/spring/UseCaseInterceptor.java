package pe.axiz.framework.hexagonal.spring;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.function.SingletonSupplier;
import pe.axiz.core.domain.EventTypes;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.framework.hexagonal.UseCaseRunner;
import pe.axiz.framework.hexagonal.annotation.UseCase;

/**
 * Wraps the public methods of {@link UseCase} classes: observation {@code axiz.usecase} with the
 * operation and outcome, one transaction and the domain event lifecycle.
 */
public class UseCaseInterceptor implements MethodInterceptor {

  private final SingletonSupplier<UseCaseRunner> runner;
  private final SingletonSupplier<ArchitectureObservation> observation;
  private final Map<Class<?>, Descriptor> descriptors = new ConcurrentHashMap<>();

  public UseCaseInterceptor(
      Supplier<UseCaseRunner> runner, Supplier<ArchitectureObservation> observation) {
    this.runner = SingletonSupplier.of(runner);
    this.observation = SingletonSupplier.of(observation);
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    Object target = Objects.requireNonNull(invocation.getThis());
    Descriptor d =
        descriptors.computeIfAbsent(AopUtils.getTargetClass(target), UseCaseInterceptor::describe);
    try {
      return observation
          .obtain()
          .observe(
              AxizObservations.USE_CASE,
              d.operation(),
              Map.of(),
              Map.of(),
              () -> runner.obtain().run(d.readOnly(), () -> proceed(invocation)));
    } catch (CheckedFailure f) {
      throw f.getCause();
    }
  }

  private static @Nullable Object proceed(MethodInvocation invocation) {
    try {
      return invocation.proceed();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new CheckedFailure(t);
    }
  }

  private static Descriptor describe(Class<?> type) {
    UseCase a = AnnotatedElementUtils.findMergedAnnotation(type, UseCase.class);
    String name =
        a != null && !a.value().isBlank() ? a.value() : EventTypes.kebab(type.getSimpleName());
    return new Descriptor(name, a != null && a.readOnly());
  }

  private record Descriptor(String operation, boolean readOnly) {}

  /** Carries a checked exception through the functional wrappers. */
  private static final class CheckedFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CheckedFailure(Throwable cause) {
      super(cause);
    }

    @Override
    public synchronized Throwable getCause() {
      return Objects.requireNonNull(super.getCause());
    }
  }
}
