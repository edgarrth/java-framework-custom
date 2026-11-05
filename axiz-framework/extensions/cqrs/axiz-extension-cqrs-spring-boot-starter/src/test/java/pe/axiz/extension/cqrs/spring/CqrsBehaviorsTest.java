package pe.axiz.extension.cqrs.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricNames;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.core.observability.AttributeSanitizer;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.extension.cqrs.AuditedCommand;
import pe.axiz.extension.cqrs.Command;
import pe.axiz.extension.cqrs.IdempotentCommand;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;
import pe.axiz.extension.cqrs.Query;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/** Each behaviour of the pipeline on its own, on both programming models. */
class CqrsBehaviorsTest {

  private static final Duration T = Duration.ofSeconds(5);

  public record BlockCard(String cardId) implements AuditedCommand<String> {
    @Override
    public String auditAction() {
      return "card.block";
    }

    @Override
    public String auditResource() {
      return "card";
    }

    @Override
    public String auditResourceId() {
      return cardId;
    }
  }

  public record Transfer(String from, String idempotencyKey) implements IdempotentCommand<String> {}

  public record Balance(String account) implements Query<String> {}

  public record Note(String text) implements Command<Void> {}

  private static <R> Invocation<R> invocation(
      pe.axiz.extension.cqrs.Request<R> request, Class<?> resultType) {
    String name =
        request
            .getClass()
            .getSimpleName()
            .replaceAll("([a-z])([A-Z])", "$1-$2")
            .toLowerCase(java.util.Locale.ROOT);
    return new Invocation<>(request, name, resultType);
  }

  private final List<AuditEvent> audited = new CopyOnWriteArrayList<>();
  private final AuditTrail trail =
      new AuditTrail(
          "cards-service",
          audited::add,
          ObjectMasker.defaults(),
          IdGenerator.uuidV7(),
          Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC));

  // --- audit -----------------------------------------------------------------------------------

  @Test
  void auditedCommandsLeaveARecordOfSuccessOrFailure() {
    AuditBehavior audit = new AuditBehavior(trail);
    assertEquals(PipelineBehavior.AUDIT, audit.order());
    assertEquals(
        "done", audit.handle(invocation(new BlockCard("c-1"), String.class), () -> "done"));
    assertThrows(
        BusinessException.class,
        () ->
            audit.handle(
                invocation(new BlockCard("c-2"), String.class),
                () -> {
                  throw new BusinessException(CoreErrors.CONFLICT, "already blocked");
                }));
    assertThrows(
        IllegalStateException.class,
        () ->
            audit.handle(
                invocation(new BlockCard("c-3"), String.class),
                () -> {
                  throw new IllegalStateException("db down");
                }));
    assertEquals(
        "balance", audit.handle(invocation(new Balance("a-1"), String.class), () -> "balance"));

    assertThat(audited).extracting(AuditEvent::resourceId).containsExactly("c-1", "c-2", "c-3");
    assertEquals(AuditEvent.Outcome.SUCCESS, audited.get(0).outcome());
    assertEquals("AXZ-CORE-0409", audited.get(1).errorCode());
    assertEquals("IllegalStateException", audited.get(2).errorCode());
  }

  @Test
  void theReactiveAuditRecordsWhenTheResultArrives() {
    ReactiveBehaviors.Audit audit =
        new ReactiveBehaviors.Audit(
            new pe.axiz.spring.audit.ReactiveAuditTrail(
                "svc",
                event -> Mono.fromRunnable(() -> audited.add(event)),
                ObjectMasker.defaults(),
                IdGenerator.uuidV7(),
                Clock.systemUTC()));
    assertEquals(PipelineBehavior.AUDIT, audit.order());
    Mono<String> pending =
        audit.handle(invocation(new BlockCard("c-1"), String.class), () -> Mono.just("done"));
    assertThat(audited).isEmpty();
    assertEquals("done", pending.block(T));
    assertNull(
        audit.handle(invocation(new BlockCard("c-2"), String.class), Mono::<String>empty).block(T));
    assertThrows(
        BusinessException.class,
        () ->
            audit
                .handle(
                    invocation(new BlockCard("c-3"), String.class),
                    () -> Mono.error(new BusinessException(CoreErrors.CONFLICT, "blocked")))
                .block(T));
    assertThrows(
        IllegalStateException.class,
        () ->
            audit
                .handle(
                    invocation(new BlockCard("c-4"), String.class),
                    () -> Mono.error(new IllegalStateException("db down")))
                .block(T));
    assertEquals(
        "b",
        audit.handle(invocation(new Balance("a-1"), String.class), () -> Mono.just("b")).block(T));

    assertThat(audited)
        .extracting(AuditEvent::outcome)
        .containsExactly(
            AuditEvent.Outcome.SUCCESS,
            AuditEvent.Outcome.SUCCESS,
            AuditEvent.Outcome.FAILURE,
            AuditEvent.Outcome.FAILURE);
    assertEquals("AXZ-CORE-0409", audited.get(2).errorCode());
    assertEquals("IllegalStateException", audited.get(3).errorCode());
  }

  // --- metrics ---------------------------------------------------------------------------------

  private static double count(SimpleMeterRegistry registry, String event, String outcome) {
    // one counter per reason: add them up
    return registry
        .get(MetricNames.COUNT)
        .tag("event", event)
        .tag("outcome", outcome)
        .counters()
        .stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  @Test
  void commandsAndQueriesAreCountedByOutcome() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MetricsBehavior metrics =
        new MetricsBehavior(new EventMetrics(registry, MetricsSettings.defaults()));
    assertEquals(PipelineBehavior.METRICS, metrics.order());
    assertEquals("b", metrics.handle(invocation(new Balance("a-1"), String.class), () -> "b"));
    assertThrows(
        BusinessException.class,
        () ->
            metrics.handle(
                invocation(new BlockCard("c-1"), String.class),
                () -> {
                  throw new BusinessException(CoreErrors.CONFLICT, "blocked");
                }));
    assertThrows(
        AssertionError.class,
        () ->
            metrics.handle(
                invocation(new BlockCard("c-2"), String.class),
                () -> {
                  throw new AssertionError("an Error passes through untouched");
                }));
    assertEquals(1.0, count(registry, "balance", "success"));
    assertEquals(2.0, count(registry, "block-card", "error"));

    ReactiveBehaviors.Metrics reactive =
        new ReactiveBehaviors.Metrics(new EventMetrics(registry, MetricsSettings.defaults()));
    assertEquals(PipelineBehavior.METRICS, reactive.order());
    assertEquals(
        "b",
        reactive
            .handle(invocation(new Balance("a-1"), String.class), () -> Mono.just("b"))
            .block(T));
    assertThrows(
        IllegalStateException.class,
        () ->
            reactive
                .handle(
                    invocation(new Balance("a-2"), String.class),
                    () -> Mono.error(new IllegalStateException("db down")))
                .block(T));
    assertEquals(2.0, count(registry, "balance", "success"));
    assertEquals(1.0, count(registry, "balance", "error"));
  }

  // --- observation -----------------------------------------------------------------------------

  /** Records each stopped observation as "name:error-or-ok". */
  private static ObservationRegistry recording(List<String> stopped) {
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                stopped.add(
                    context.getContextualName()
                        + ":"
                        + (context.getError() == null
                            ? "ok"
                            : context.getError().getClass().getSimpleName()));
              }
            });
    return registry;
  }

  @Test
  void eachRequestIsOneObservationThatEndsWithItsOutcome() {
    List<String> stopped = new CopyOnWriteArrayList<>();
    ArchitectureObservation observation =
        new ArchitectureObservation(
            recording(stopped), e -> e instanceof BusinessException, AttributeSanitizer.defaults());

    ObservationBehavior blocking = new ObservationBehavior(observation);
    assertEquals(0, blocking.order());
    assertEquals("b", blocking.handle(invocation(new Balance("a-1"), String.class), () -> "b"));
    assertEquals("x", blocking.handle(invocation(new BlockCard("c-1"), String.class), () -> "x"));

    ReactiveBehaviors.Observation reactive = new ReactiveBehaviors.Observation(observation);
    assertEquals(0, reactive.order());
    assertEquals(
        "b",
        reactive
            .handle(invocation(new Balance("a-2"), String.class), () -> Mono.just("b"))
            .block(T));
    assertThrows(
        IllegalStateException.class,
        () ->
            reactive
                .handle(
                    invocation(new BlockCard("c-2"), String.class),
                    () -> Mono.error(new IllegalStateException("db down")))
                .block(T));
    Disposable waiting =
        reactive
            .handle(invocation(new BlockCard("c-3"), String.class), Mono::<String>never)
            .subscribe();
    waiting.dispose();

    assertThat(stopped)
        .containsExactly(
            "balance:ok",
            "block-card:ok",
            "balance:ok",
            "block-card:IllegalStateException",
            "block-card:CancellationException");
  }

  // --- transactions ----------------------------------------------------------------------------

  /** Records whether each transaction was read-only and how it ended. */
  static final class RecordingTransactions implements PlatformTransactionManager {
    final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      events.add(definition != null && definition.isReadOnly() ? "begin-read" : "begin-write");
      return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {
      events.add("commit");
    }

    @Override
    public void rollback(TransactionStatus status) {
      events.add("rollback");
    }
  }

  @Test
  void commandsWriteQueriesOnlyReadAndAFailureRollsBack() {
    RecordingTransactions transactions = new RecordingTransactions();
    TransactionBehavior tx = new TransactionBehavior(transactions);
    assertEquals(PipelineBehavior.TRANSACTION, tx.order());
    assertEquals("x", tx.handle(invocation(new BlockCard("c-1"), String.class), () -> "x"));
    assertEquals("b", tx.handle(invocation(new Balance("a-1"), String.class), () -> "b"));
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.handle(
                invocation(new BlockCard("c-2"), String.class),
                () -> {
                  throw new IllegalStateException("db down");
                }));
    assertThat(transactions.events)
        .containsExactly(
            "begin-write", "commit", "begin-read", "commit", "begin-write", "rollback");
  }

  @Test
  void theReactiveTransactionCoversTheWholeHandler() {
    FakeR2dbc db = new FakeR2dbc();
    ReactiveBehaviors.Transaction tx =
        new ReactiveBehaviors.Transaction(new R2dbcTransactionManager(db));
    assertEquals(PipelineBehavior.TRANSACTION, tx.order());
    assertEquals(
        "x",
        tx.handle(invocation(new BlockCard("c-1"), String.class), () -> Mono.just("x")).block(T));
    assertEquals(
        "b",
        tx.handle(invocation(new Balance("a-1"), String.class), () -> Mono.just("b")).block(T));
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.handle(
                    invocation(new BlockCard("c-2"), String.class),
                    () -> Mono.error(new IllegalStateException("db down")))
                .block(T));
    assertThat(db.events()).anyMatch(e -> e.startsWith("commit:"));
    assertThat(db.events()).anyMatch(e -> e.startsWith("rollback:"));
  }

  // --- validation ------------------------------------------------------------------------------

  /** Source of a real constraint annotation for the fake violation. */
  static final class Annotated {
    @NotBlank String cardId = "";
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> answers) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) ->
                switch (m.getName()) {
                  case "hashCode" -> System.identityHashCode(p);
                  case "equals" -> p == a[0];
                  default -> answers.apply(m.getName());
                });
  }

  /** A validator that rejects every request whose text is blank. */
  private static Validator validator() throws NoSuchFieldException {
    Annotation notBlank = Annotated.class.getDeclaredField("cardId").getAnnotation(NotBlank.class);
    ConstraintDescriptor<?> descriptor =
        proxy(ConstraintDescriptor.class, m -> "getAnnotation".equals(m) ? notBlank : null);
    Object path = proxy(jakarta.validation.Path.class, m -> "toString".equals(m) ? "text" : null);
    ConstraintViolation<Object> violation =
        proxy(
            ConstraintViolation.class,
            m ->
                switch (m) {
                  case "getPropertyPath" -> path;
                  case "getConstraintDescriptor" -> descriptor;
                  case "getMessage" -> "must not be blank";
                  default -> null;
                });
    return (Validator)
        Proxy.newProxyInstance(
            Validator.class.getClassLoader(),
            new Class<?>[] {Validator.class},
            (p, m, a) ->
                switch (m.getName()) {
                  case "validate" ->
                      a[0] instanceof Note n && n.text().isBlank() ? Set.of(violation) : Set.of();
                  case "hashCode" -> System.identityHashCode(p);
                  case "equals" -> p == a[0];
                  default -> throw new UnsupportedOperationException(m.getName());
                });
  }

  @Test
  void invalidRequestsStopBeforeTheHandler() throws Exception {
    Validator validator = validator();
    AtomicInteger handled = new AtomicInteger();
    ValidationBehavior blocking = new ValidationBehavior(validator);
    assertEquals(PipelineBehavior.VALIDATION, blocking.order());
    ValidationException invalid =
        assertThrows(
            ValidationException.class,
            () ->
                blocking.handle(
                    invocation(new Note(" "), Void.class),
                    () -> {
                      handled.incrementAndGet();
                      return null;
                    }));
    assertThat(invalid.violations()).extracting(v -> v.field()).containsExactly("text");
    assertNull(
        blocking.handle(
            invocation(new Note("ok"), Void.class),
            () -> {
              handled.incrementAndGet();
              return null;
            }));

    ReactiveBehaviors.Validation reactive = new ReactiveBehaviors.Validation(validator);
    assertEquals(PipelineBehavior.VALIDATION, reactive.order());
    assertThrows(
        ValidationException.class,
        () ->
            reactive
                .handle(
                    invocation(new Note(""), Void.class),
                    () -> Mono.fromRunnable(handled::incrementAndGet))
                .block(T));
    reactive
        .handle(
            invocation(new Note("ok"), Void.class),
            () -> Mono.fromRunnable(handled::incrementAndGet))
        .block(T);
    assertEquals(2, handled.get(), "only the valid requests reached the handler");
  }

  // --- reactive idempotency --------------------------------------------------------------------

  @Test
  void aRepeatedKeyGetsTheStoredResultAndAFailureCanBeRetried() {
    pe.axiz.core.idempotency.AsyncIdempotencyService service =
        new pe.axiz.core.idempotency.AsyncIdempotencyService(
            new pe.axiz.core.idempotency.InMemoryAsyncIdempotencyStore(Clock.systemUTC()),
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Clock.systemUTC());
    ReactiveBehaviors.Idempotency idempotency =
        new ReactiveBehaviors.Idempotency(
            service, tools.jackson.databind.json.JsonMapper.builder().build());
    assertEquals(PipelineBehavior.IDEMPOTENCY, idempotency.order());
    AtomicInteger runs = new AtomicInteger();

    String first =
        idempotency
            .handle(
                invocation(new Transfer("a-1", "idempotency-key-00000001"), String.class),
                () -> Mono.fromCallable(() -> "t-" + runs.incrementAndGet()))
            .block(T);
    String again =
        idempotency
            .handle(
                invocation(new Transfer("a-1", "idempotency-key-00000001"), String.class),
                () -> Mono.fromCallable(() -> "t-" + runs.incrementAndGet()))
            .block(T);
    assertEquals("t-1", first);
    assertEquals(first, again, "replayed, not executed again");

    assertThrows(
        IllegalStateException.class,
        () ->
            idempotency
                .handle(
                    invocation(new Transfer("a-2", "idempotency-key-00000002"), String.class),
                    () -> Mono.error(new IllegalStateException("core down")))
                .block(T));
    var uncertain =
        assertThrows(
            pe.axiz.core.errors.BusinessException.class,
            () ->
                idempotency
                    .handle(
                        invocation(new Transfer("a-2", "idempotency-key-00000002"), String.class),
                        () -> Mono.fromCallable(() -> "t-" + runs.incrementAndGet()))
                    .block(T));
    assertEquals(
        pe.axiz.core.idempotency.IdempotencyErrors.OUTCOME_UNKNOWN.code(), uncertain.code());
    assertEquals(1, runs.get(), "an uncertain effect is never executed twice");

    assertNull(
        idempotency
            .handle(
                invocation(new Transfer("a-3", "idempotency-key-00000003"), String.class),
                Mono::<String>empty)
            .block(T));
    assertNull(
        idempotency
            .handle(
                invocation(new Transfer("a-3", "idempotency-key-00000003"), String.class),
                () -> Mono.just("not-called"))
            .block(T),
        "an empty result is replayed as empty");

    assertEquals(
        "b",
        idempotency
            .handle(invocation(new Balance("a-1"), String.class), () -> Mono.just("b"))
            .block(T));
  }
}
