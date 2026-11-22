package pe.axiz.structure.hexagonal.spring;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.util.function.SingletonSupplier;
import pe.axiz.core.domain.EventTypes;
import pe.axiz.structure.hexagonal.annotation.UseCase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Defers invocation and keeps transaction, observation, errors and cancellation in the publisher.
 */
public final class ReactiveUseCaseInterceptor implements MethodInterceptor {
  private final Supplier<@Nullable ReactiveTransactionManager> transactions;

  private final SingletonSupplier<ObservationRegistry> observations;

  /**
   * Creates the interceptor.
   *
   * @param transactions the reactive transaction manager, if any
   * @param observations the observation registry, resolved on first use: an advisor is created
   *     before bean post-processors run, and resolving the registry then would leave it without its
   *     tracing and metrics handlers
   */
  public ReactiveUseCaseInterceptor(
      Supplier<@Nullable ReactiveTransactionManager> transactions,
      Supplier<ObservationRegistry> observations) {
    this.transactions = Objects.requireNonNull(transactions);
    this.observations = SingletonSupplier.of(Objects.requireNonNull(observations));
  }

  @Override
  public Object invoke(MethodInvocation invocation) {
    Mono<Object> work =
        Mono.deferContextual(
            context -> {
              Object target = Objects.requireNonNull(invocation.getThis());
              Class<?> type = AopUtils.getTargetClass(target);
              UseCase annotation = AnnotatedElementUtils.findMergedAnnotation(type, UseCase.class);
              String operation =
                  annotation != null && !annotation.value().isBlank()
                      ? annotation.value()
                      : EventTypes.kebab(type.getSimpleName());
              Observation observation =
                  Observation.createNotStarted("axiz.usecase", observations.obtain())
                      .lowCardinalityKeyValue("operation", operation);
              Observation parent = context.getOrDefault("micrometer.observation", null);
              if (parent != null) observation.parentObservation(parent);
              observation.start();
              Mono<Object> result =
                  Mono.defer(
                      () -> {
                        try {
                          Object value =
                              invocation instanceof ProxyMethodInvocation proxy
                                  ? proxy.invocableClone().proceed()
                                  : invocation.proceed();
                          if (!(value instanceof Publisher<?> publisher))
                            return Mono.error(
                                new IllegalStateException(
                                    "Reactive use cases must return Publisher"));
                          return Flux.from(publisher).singleOrEmpty().cast(Object.class);
                        } catch (Throwable failure) {
                          return Mono.error(failure);
                        }
                      });
              ReactiveTransactionManager manager = transactions.get();
              if (manager != null) {
                DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
                definition.setReadOnly(annotation != null && annotation.readOnly());
                result = TransactionalOperator.create(manager, definition).transactional(result);
              }
              return result
                  .doOnError(observation::error)
                  .doFinally(signal -> observation.stop())
                  .contextWrite(ctx -> ctx.put("micrometer.observation", observation));
            });
    return Flux.class.isAssignableFrom(invocation.getMethod().getReturnType()) ? work.flux() : work;
  }
}
