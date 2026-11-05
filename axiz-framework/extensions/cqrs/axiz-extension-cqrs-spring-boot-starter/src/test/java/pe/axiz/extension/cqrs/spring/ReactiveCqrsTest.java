package pe.axiz.extension.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.InMemoryAsyncIdempotencyStore;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.extension.cqrs.CommandHandler;
import pe.axiz.extension.cqrs.HandlerNotFoundException;
import pe.axiz.extension.cqrs.IdempotentCommand;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.Query;
import pe.axiz.extension.cqrs.ReactiveAccessPolicy;
import pe.axiz.extension.cqrs.ReactiveCommandHandler;
import pe.axiz.extension.cqrs.ReactiveQueryHandler;
import pe.axiz.extension.cqrs.RequiresAuthority;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;

class ReactiveCqrsTest {

  private static final Duration T = Duration.ofSeconds(5);

  @RequiresAuthority("SCOPE_payments:write")
  public record PayCommand(String paymentId, String idempotencyKey)
      implements IdempotentCommand<String> {}

  public record PaymentStatusQuery(String paymentId) implements Query<String> {}

  public record UnknownQuery() implements Query<String> {}

  /** Changes a row and reads it back in the same reactive transaction. */
  public static class PayHandler implements ReactiveCommandHandler<PayCommand, String> {
    final AtomicInteger calls = new AtomicInteger();
    final DatabaseClient db;

    PayHandler(DatabaseClient db) {
      this.db = db;
    }

    @Override
    public Mono<String> handle(PayCommand command) {
      return db.sql("UPDATE payments SET status = 'PAID' WHERE id = :id")
          .bind("id", command.paymentId())
          .fetch()
          .rowsUpdated()
          .then(Mono.fromSupplier(() -> "paid-" + calls.incrementAndGet()));
    }
  }

  public static class StatusHandler implements ReactiveQueryHandler<PaymentStatusQuery, String> {
    final DatabaseClient db;

    StatusHandler(DatabaseClient db) {
      this.db = db;
    }

    @Override
    public Mono<String> handle(PaymentStatusQuery query) {
      return db.sql("SELECT status FROM payments WHERE id = :id")
          .bind("id", query.paymentId())
          .map(r -> r.get("status", String.class))
          .one();
    }
  }

  @Test
  void reactivePipelineRunsWithoutBlocking() {
    FakeR2dbc fake =
        new FakeR2dbc()
            .onUpdate("UPDATE payments", b -> 1L)
            .onQuery("SELECT status", b -> List.of(Map.of("status", "PAID")));
    DatabaseClient db = DatabaseClient.create(fake);
    PayHandler pay = new PayHandler(db);
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizCqrsAutoConfiguration.class, AxizReactiveCqrsAutoConfiguration.class))
        .withBean(PayHandler.class, () -> pay)
        .withBean(StatusHandler.class, () -> new StatusHandler(db))
        .withBean(OwnerOnly.class, OwnerOnly::new)
        .withBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(fake))
        .withBean(
            AsyncIdempotencyService.class,
            () ->
                new AsyncIdempotencyService(
                    new InMemoryAsyncIdempotencyStore(Clock.systemUTC()),
                    Duration.ofSeconds(30),
                    Duration.ofHours(1),
                    Clock.systemUTC()))
        .run(
            ctx -> {
              ReactiveCommandBus commands = ctx.getBean(ReactiveCommandBus.class);
              ReactiveQueryBus queries = ctx.getBean(ReactiveQueryBus.class);
              PayCommand command = new PayCommand("p-1", "idempotency-key-00000001");

              Mono<String> anonymous = commands.send(command);
              assertThrows(AccessException.class, () -> anonymous.block(T));
              assertEquals(0, pay.calls.get());

              TestingAuthenticationToken user =
                  new TestingAuthenticationToken("u", "p", "SCOPE_payments:write");
              String first =
                  commands
                      .send(command)
                      .contextWrite(ReactiveSecurityContextHolder.withAuthentication(user))
                      .block(T);
              String replay =
                  commands
                      .send(command)
                      .contextWrite(ReactiveSecurityContextHolder.withAuthentication(user))
                      .block(T);
              assertEquals("paid-1", first);
              assertEquals(first, replay);
              assertEquals(1, pay.calls.get());
              int connection = fake.last("UPDATE payments").connection();
              assertTrue(fake.events().contains("commit:" + connection));

              assertEquals(
                  "PAID",
                  queries
                      .ask(new PaymentStatusQuery("p-1"))
                      .contextWrite(
                          ReactiveSecurityContextHolder.withAuthentication(
                              new TestingAuthenticationToken("owner", "p", "SCOPE_x")))
                      .block(T));
              assertThrows(
                  HandlerNotFoundException.class, () -> queries.ask(new UnknownQuery()).block(T));
            });
  }

  @Test
  void aRequestCannotHaveBothStyles() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizCqrsAutoConfiguration.class, AxizReactiveCqrsAutoConfiguration.class))
        .withBean(BlockingPay.class, BlockingPay::new)
        .withBean(PayHandler.class, () -> new PayHandler(DatabaseClient.create(new FakeR2dbc())))
        .run(ctx -> assertNotNull(ctx.getStartupFailure()));
  }

  public static class BlockingPay implements CommandHandler<PayCommand, String> {
    @Override
    public String handle(PayCommand command) {
      return "blocking";
    }
  }

  /** Owner check that reads the owner reactively, as a reactive repository would. */
  static final class OwnerOnly implements ReactiveAccessPolicy<PaymentStatusQuery> {
    final AtomicInteger lookups = new AtomicInteger();

    @Override
    public Class<PaymentStatusQuery> requestType() {
      return PaymentStatusQuery.class;
    }

    @Override
    public org.reactivestreams.Publisher<Void> check(PaymentStatusQuery request, Caller caller) {
      return Mono.fromSupplier(
              () -> {
                lookups.incrementAndGet();
                return "owner"; // the owner a reactive repository would return
              })
          .filter(owner -> owner.equals(caller.subject()))
          .switchIfEmpty(Mono.error(new NotFoundException("payment", request.paymentId())))
          .then()
          .flux();
    }
  }

  @Test
  void reactivePoliciesDenyBeforeTheHandlerWithoutBlocking() {
    OwnerOnly policy = new OwnerOnly();
    ReactiveBehaviors.Authorization authorization =
        new ReactiveBehaviors.Authorization(
            pe.axiz.extension.cqrs.AccessPolicies.none(), List.of(policy));
    AtomicInteger handled = new AtomicInteger();
    Invocation<String> invocation =
        new Invocation<>(new PaymentStatusQuery("p-1"), "payment-status-query", String.class);
    ReactivePipelineBehavior.Next<String> next =
        () -> Mono.fromSupplier(() -> "PAID-" + handled.incrementAndGet());

    Mono<String> stranger =
        authorization
            .handle(invocation, next)
            .contextWrite(
                ReactiveSecurityContextHolder.withAuthentication(
                    new TestingAuthenticationToken("other", "p", "SCOPE_x")));
    assertThrows(NotFoundException.class, () -> stranger.block(T));
    assertEquals(0, handled.get());

    TestingAuthenticationToken owner = new TestingAuthenticationToken("owner", "p", "SCOPE_x");
    assertEquals(
        "PAID-1",
        authorization
            .handle(invocation, next)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(owner))
            .block(T));
    assertEquals(2, policy.lookups.get());
  }

  @Test
  void reactivePoliciesCoverRequestsWhenUnprotectedRequestsFailStartup() {
    DatabaseClient db = DatabaseClient.create(new FakeR2dbc());
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AxizCqrsAutoConfiguration.class, AxizReactiveCqrsAutoConfiguration.class))
            .withBean(StatusHandler.class, () -> new StatusHandler(db));
    runner.run(ctx -> assertNotNull(ctx.getStartupFailure()));
    runner
        .withBean(OwnerOnly.class, OwnerOnly::new)
        .run(ctx -> assertNull(ctx.getStartupFailure()));
  }
}
