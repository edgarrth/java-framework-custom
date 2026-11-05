package pe.axiz.extension.cqrs.spring;

import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.NoEffect;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.extension.cqrs.AccessPolicies;
import pe.axiz.extension.cqrs.AuditedCommand;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.extension.cqrs.IdempotentCommand;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;
import pe.axiz.extension.cqrs.ReactiveAccessPolicy;
import pe.axiz.extension.cqrs.RequiresAuthority;
import pe.axiz.spring.audit.ReactiveAuditTrail;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** The standard reactive pipeline behaviors, one per cross-cutting concern. */
public final class ReactiveBehaviors {

  private ReactiveBehaviors() {}

  /**
   * Outermost step: a span {@code axiz.command}/{@code axiz.query} that is the parent of every span
   * created downstream (R2DBC statements, HTTP calls), through the Reactor context.
   */
  public static final class Observation implements ReactivePipelineBehavior {

    private final ArchitectureObservation observation;

    public Observation(ArchitectureObservation observation) {
      this.observation = Objects.requireNonNull(observation);
    }

    @Override
    public int order() {
      return 0;
    }

    @Override
    public <R> Mono<R> handle(Invocation<R> invocation, Next<R> next) {
      return Mono.defer(
          () -> {
            ArchitectureObservation.Started started =
                observation.start(
                    invocation.isCommand() ? AxizObservations.COMMAND : AxizObservations.QUERY,
                    invocation.name(),
                    Map.of(),
                    Map.of());
            return next.proceed()
                .doOnSuccess(r -> started.succeed())
                .doOnError(started::fail)
                .doOnCancel(() -> started.fail(new CancellationException("cancelled")))
                .contextWrite(
                    c -> c.put(ObservationThreadLocalAccessor.KEY, started.observation()));
          });
    }
  }

  /** Bean Validation of the request before anything else runs (400 on violations). */
  public static final class Validation implements ReactivePipelineBehavior {

    private final Validator validator;

    public Validation(Validator validator) {
      this.validator = Objects.requireNonNull(validator);
    }

    @Override
    public int order() {
      return PipelineBehavior.VALIDATION;
    }

    @Override
    public <R> Mono<R> handle(Invocation<R> invocation, Next<R> next) {
      return Mono.fromRunnable(() -> ValidationBehavior.validate(validator, invocation.request()))
          .then(Mono.defer(next::proceed));
    }
  }

  /**
   * {@link RequiresAuthority} and the matching access policies, checked against the reactive
   * security context before idempotency (403/404). External state is read by native reactive
   * policies.
   */
  public static final class Authorization implements ReactivePipelineBehavior {

    private final List<ReactiveAccessPolicy<?>> reactivePolicies;

    public Authorization() {
      this(AccessPolicies.none());
    }

    public Authorization(AccessPolicies policies) {
      this(policies, List.of());
    }

    /**
     * Authorization for reactive buses.
     *
     * @param policies must be empty; imperative policies are not accepted by a reactive bus
     * @param reactivePolicies native non-blocking policies
     */
    public Authorization(
        AccessPolicies policies, List<? extends ReactiveAccessPolicy<?>> reactivePolicies) {
      if (!Objects.requireNonNull(policies).isEmpty())
        throw new IllegalArgumentException(
            "Reactive CQRS requires ReactiveAccessPolicy; imperative access policies are not"
                + " supported");
      this.reactivePolicies = List.copyOf(reactivePolicies);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> checkReactive(Object request, Caller caller) {
      Mono<Void> chain = Mono.empty();
      for (ReactiveAccessPolicy policy : reactivePolicies) {
        if (policy.requestType().isInstance(request)) {
          chain = chain.then(Mono.defer(() -> Mono.from(policy.check(request, caller))));
        }
      }
      return chain;
    }

    @Override
    public int order() {
      return PipelineBehavior.AUTHORIZATION;
    }

    @Override
    public <R> Mono<R> handle(Invocation<R> invocation, Next<R> next) {
      RequiresAuthority rule = AuthorizationBehavior.rule(invocation);
      return Mono.deferContextual(
          ctx -> {
            RequestMetadata metadata = ctx.getOrDefault(RequestContext.KEY, null);
            return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(
                    auth -> {
                      if (rule != null) {
                        AuthorizationBehavior.check(rule, auth.orElse(null), invocation.name());
                      }
                      Caller caller = AuthorizationBehavior.caller(auth.orElse(null), metadata);
                      return checkReactive(invocation.request(), caller);
                    })
                .then(Mono.defer(next::proceed));
          });
    }
  }

  /**
   * Idempotent commands: the first result is stored and replayed for the same key and command; the
   * store is native asynchronous and cancellation releases the execution lease.
   */
  public static final class Idempotency implements ReactivePipelineBehavior {

    private final AsyncIdempotencyService service;
    private final JsonMapper mapper;

    /** Retained descriptor; reactive buses reject an imperative service at construction. */
    public Idempotency(IdempotencyService service, JsonMapper mapper) {
      throw new IllegalArgumentException("Reactive CQRS requires AsyncIdempotencyService");
    }

    public Idempotency(AsyncIdempotencyService service, JsonMapper mapper) {
      this.service = Objects.requireNonNull(service);
      this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public int order() {
      return PipelineBehavior.IDEMPOTENCY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> Mono<R> handle(Invocation<R> invocation, Next<R> next) {
      if (!(invocation.request() instanceof IdempotentCommand<?> command)) {
        return next.proceed();
      }
      return Mono.deferContextual(
              ctx -> {
                RequestMetadata caller = ctx.getOrDefault(RequestContext.KEY, null);
                byte[] body = mapper.writeValueAsBytes(command);
                String fingerprint =
                    IdempotencyService.fingerprint("COMMAND", invocation.name(), body);
                return ReactiveStages.from(
                    () ->
                        service.begin(
                            IdempotencyBehavior.scope(invocation.name(), caller),
                            command.idempotencyKey(),
                            fingerprint));
              })
          .flatMap(
              decision -> {
                if (decision instanceof IdempotencyService.Replay(StoredResponse stored)) {
                  return stored.body().length == 0
                      ? Mono.<R>empty()
                      : Mono.fromSupplier(
                          () -> (R) mapper.readValue(stored.body(), invocation.resultType()));
                }
                IdempotencyService.Proceed execution = (IdempotencyService.Proceed) decision;
                return Mono.defer(
                    () -> {
                      var lease = service.startLease(execution);
                      Mono<R> tracked =
                          Mono.defer(next::proceed)
                              .materialize()
                              .flatMap(
                                  signal -> {
                                    Throwable original = signal.getThrowable();
                                    if (original != null) return Mono.error(original);
                                    @Nullable R value = signal.get();
                                    Mono<Void> delivery =
                                        complete(
                                            execution,
                                            value == null
                                                ? new byte[0]
                                                : mapper.writeValueAsBytes(value));
                                    return value == null
                                        ? delivery.then(Mono.empty())
                                        : delivery.thenReturn(value);
                                  });
                      return Mono.usingWhen(
                              Mono.just(execution),
                              ignored -> tracked,
                              ignored -> Mono.empty(),
                              (ignored, error) -> release(execution, error),
                              ignored -> release(execution, new CancellationException("cancelled")))
                          .doFinally(signal -> lease.close());
                    });
              });
    }

    private Mono<Void> release(IdempotencyService.Proceed execution, Throwable original) {
      return ReactiveStages.from(
              () ->
                  original instanceof NoEffect
                      ? service.releaseWithoutEffect(execution)
                      : service.fail(execution))
          .then()
          .onErrorResume(
              failure -> {
                if (failure != original) original.addSuppressed(failure);
                LoggerFactory.getLogger(Idempotency.class)
                    .warn("Idempotency lease could not be released", failure);
                return Mono.empty();
              });
    }

    private Mono<Void> complete(IdempotencyService.Proceed execution, byte[] body) {
      return ReactiveStages.from(
              () ->
                  service.complete(
                      execution,
                      new StoredResponse(200, Map.of("Content-Type", "application/json"), body)))
          .doOnNext(
              stored -> {
                if (!stored) {
                  LoggerFactory.getLogger(Idempotency.class)
                      .warn(
                          "Idempotency key lost before completion (lock expired); result not"
                              + " stored: {}",
                          execution.key());
                }
              })
          .then();
    }
  }

  /** Commands in a reactive read-write transaction, queries in a read-only one. */
  public static final class Transaction implements ReactivePipelineBehavior {

    private final TransactionalOperator write;
    private final TransactionalOperator read;

    public Transaction(ReactiveTransactionManager transactions) {
      this.write = TransactionalOperator.create(Objects.requireNonNull(transactions));
      DefaultTransactionDefinition readOnly = new DefaultTransactionDefinition();
      readOnly.setReadOnly(true);
      this.read = TransactionalOperator.create(transactions, readOnly);
    }

    @Override
    public int order() {
      return PipelineBehavior.TRANSACTION;
    }

    @Override
    public <R> Mono<R> handle(Invocation<R> invocation, Next<R> next) {
      return (invocation.isCommand() ? write : read).transactional(Mono.defer(next::proceed));
    }
  }

  /** Records {@link AuditedCommand} outcomes through native delivery in the Reactor context. */
  public static final class Audit implements ReactivePipelineBehavior {
    private final ReactiveAuditTrail trail;

    /** Retained descriptor; reactive buses reject an imperative publisher at construction. */
    public Audit(AuditTrail trail) {
      throw new IllegalArgumentException("Reactive CQRS requires ReactiveAuditTrail");
    }

    public Audit(ReactiveAuditTrail trail) {
      this.trail = Objects.requireNonNull(trail);
    }

    @Override
    public int order() {
      return PipelineBehavior.AUDIT;
    }

    @Override
    public <R> Mono<R> handle(Invocation<R> invocation, Next<R> next) {
      if (!(invocation.request() instanceof AuditedCommand<?> command))
        return Mono.defer(next::proceed);
      return Mono.deferContextual(
          ctx -> {
            RequestMetadata metadata = ctx.getOrDefault(RequestContext.KEY, null);
            AuditTrail.Context actor =
                metadata == null
                    ? AuditTrail.Context.system()
                    : new AuditTrail.Context(
                        new AuditEvent.Actor(metadata.subject(), metadata.clientId(), null),
                        metadata.correlationId(),
                        metadata.channel(),
                        metadata.tenantId());
            var record =
                trail
                    .record(command.auditAction(), command.auditResource())
                    .resourceId(command.auditResourceId())
                    .context(actor)
                    .detail("command", command);
            return record.around(Mono.defer(next::proceed));
          });
    }
  }

  /** Counts and times every command and query as an event named after the request. */
  public static final class Metrics implements ReactivePipelineBehavior {

    private final EventMetrics metrics;

    public Metrics(EventMetrics metrics) {
      this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public int order() {
      return PipelineBehavior.METRICS;
    }

    @Override
    public <R> Mono<R> handle(Invocation<R> invocation, Next<R> next) {
      return Mono.defer(
          () -> {
            EventMetrics.Observed observed =
                metrics.begin(
                    invocation.name(),
                    invocation.isCommand() ? "command" : "query",
                    true,
                    List.of(invocation.request()));
            return next.proceed().doOnSuccess(observed::succeed).doOnError(observed::fail);
          });
    }
  }
}
