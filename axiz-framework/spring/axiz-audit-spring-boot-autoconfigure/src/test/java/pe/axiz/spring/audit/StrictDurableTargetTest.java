package pe.axiz.spring.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.audit.AuditChainState;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.kit.reactor.audit.ReactiveAuditChainState;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;
import reactor.core.publisher.Mono;

class StrictDurableTargetTest {
  private static AxizAuditProperties properties() {
    return new AxizAuditProperties(
        new AxizAuditProperties.Async(1, Duration.ZERO, Duration.ofSeconds(1)),
        new AxizAuditProperties.Http(false, List.of(), List.of(), List.of()),
        new AxizAuditProperties.Durable(
            "audit", AuditOutsideTransaction.FAIL, Duration.ofSeconds(1), 1),
        new AxizAuditProperties.Integrity(AuditSealer.Mode.HASH, null, null),
        new AxizAuditProperties.Strict(
            true,
            AxizAuditProperties.StrictMode.DURABLE,
            Duration.ofMillis(1500),
            1,
            Duration.ofMinutes(1)),
        AxizAuditProperties.Mode.DURABLE);
  }

  @Test
  void imperativeStrictTargetCommitsTheSealedOutboxWithItsOwnTransaction() throws Exception {
    var fixture = new DurableAuditTest();
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("writer", new OutboxWriter(fixture.store(), Clock.systemUTC()));
    beans.registerSingleton("state", AuditTestTransactions.state());
    beans.registerSingleton("transactions", new AuditTestTransactions.Jdbc());
    var properties = properties();
    try (var integrity =
        new AuditIntegritySupport(
            properties.integrity(),
            new MockEnvironment().withProperty("axiz.config.environment", "test"),
            beans,
            Clock.systemUTC())) {
      StrictAuditTargets.durable(beans, properties, integrity)
          .apply(DurableAuditTest.event(AuditEvent.Outcome.SUCCESS))
          .toCompletableFuture()
          .get(2, TimeUnit.SECONDS);
      assertEquals(1, fixture.outbox.size());
      assertEquals("audit", fixture.outbox.getFirst().destination());
      beans.destroySingleton("state");
      var mismatch = mock(AuditChainState.class);
      beans.registerSingleton("state", mismatch);
      assertThrows(
          IllegalArgumentException.class,
          () -> StrictAuditTargets.durable(beans, properties, integrity));
      when(mismatch.transactionResource()).thenReturn(new Object());
      assertThrows(
          IllegalArgumentException.class,
          () -> StrictAuditTargets.durable(beans, properties, integrity));
      assertEquals(1, fixture.outbox.size());
    }
  }

  @Test
  void reactiveStrictTargetRequiresTheSameResourceAndCompletesTheNativeWrite() throws Exception {
    var beans = new DefaultListableBeanFactory();
    var writer = mock(ReactiveOutboxWriter.class);
    when(writer.transactionResource()).thenReturn(AuditTestTransactions.RESOURCE);
    when(writer.write(any(), any(), any())).thenReturn(Mono.empty());
    beans.registerSingleton("writer", writer);
    beans.registerSingleton("state", AuditTestTransactions.reactiveState());
    beans.registerSingleton("transactions", new AuditTestTransactions.Reactive());
    var properties = properties();
    try (var integrity =
        new AuditIntegritySupport(
            properties.integrity(),
            new MockEnvironment().withProperty("axiz.config.environment", "test"),
            beans,
            Clock.systemUTC())) {
      ReactiveStrictAuditTargets.durable(beans, properties, integrity)
          .apply(DurableAuditTest.event(AuditEvent.Outcome.SUCCESS))
          .toCompletableFuture()
          .get(2, TimeUnit.SECONDS);
      verify(writer).write(eq("audit"), any(), eq("audit"));
      beans.destroySingleton("state");
      var mismatch = mock(ReactiveAuditChainState.class);
      beans.registerSingleton("state", mismatch);
      assertThrows(
          IllegalArgumentException.class,
          () -> ReactiveStrictAuditTargets.durable(beans, properties, integrity));
      when(mismatch.transactionResource()).thenReturn(new Object());
      assertThrows(
          IllegalArgumentException.class,
          () -> ReactiveStrictAuditTargets.durable(beans, properties, integrity));
    }
  }
}
