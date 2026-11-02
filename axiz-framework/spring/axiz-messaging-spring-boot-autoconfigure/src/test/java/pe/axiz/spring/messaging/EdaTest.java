package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.core.messaging.TypedEventHandler;

class EdaTest {

  record CardIssued(String id) {}

  @HandlesEvent("pe.axiz.cards.card-issued.v1")
  static class CardIssuedHandler extends TypedEventHandler<CardIssued> {
    final AtomicBoolean inTx = new AtomicBoolean();

    @Override
    protected void on(CardIssued payload, EventEnvelope envelope) {
      inTx.set(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  static class FakeTx extends AbstractPlatformTransactionManager {
    private static final long serialVersionUID = 1L;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(
        Object transaction, org.springframework.transaction.TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }

  @Test
  void handlersRunInATransaction() {
    CardIssuedHandler handler = new CardIssuedHandler();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizMessagingAutoConfiguration.class))
        .withBean(PlatformTransactionManager.class, FakeTx::new)
        .withBean("cardIssuedHandler", CardIssuedHandler.class, () -> handler)
        .run(
            ctx -> {
              EventHandler<?> bean = (EventHandler<?>) ctx.getBean("cardIssuedHandler");
              assertInstanceOf(TransactionalEventHandler.class, bean);
              assertEquals("pe.axiz.cards.card-issued.v1", bean.eventType());
              @SuppressWarnings("unchecked")
              EventHandler<CardIssued> typed = (EventHandler<CardIssued>) bean;
              typed.handle(
                  new CardIssued("c1"),
                  new EventEnvelope(
                      "e1",
                      "s",
                      "pe.axiz.cards.card-issued.v1",
                      java.time.Instant.EPOCH,
                      null,
                      "application/json",
                      new byte[0],
                      java.util.Map.of()));
              assertTrue(handler.inTx.get());
            });
  }

  /** A handler that fails with a checked exception. */
  static final class Failing implements EventHandler<String> {
    @Override
    public String eventType() {
      return "pe.axiz.cards.card-blocked.v1";
    }

    @Override
    public Class<String> payloadType() {
      return String.class;
    }

    @Override
    public void handle(String payload, EventEnvelope envelope) throws Exception {
      if ("io".equals(payload)) {
        throw new IOException("statement store unreachable");
      }
      throw new IllegalStateException("bad state");
    }
  }

  @Test
  void aCheckedFailureLeavesTheTransactionAsItself() {
    Failing delegate = new Failing();
    TransactionalEventHandler<String> handler =
        new TransactionalEventHandler<>(delegate, new TransactionTemplate(new EdaTest.FakeTx()));
    assertSame(delegate, handler.delegate());
    assertEquals(String.class, handler.payloadType());
    assertEquals("pe.axiz.cards.card-blocked.v1", handler.eventType());
    IOException io =
        assertThrows(
            IOException.class,
            () ->
                handler.handle(
                    "io",
                    new EventEnvelope(
                        "e",
                        "urn:test",
                        "pe.axiz.cards.card-blocked.v1",
                        java.time.Instant.EPOCH,
                        null,
                        "application/json",
                        new byte[0],
                        java.util.Map.of())));
    assertEquals("statement store unreachable", io.getMessage());
    assertThrows(
        IllegalStateException.class,
        () ->
            handler.handle(
                "state",
                new EventEnvelope(
                    "e",
                    "urn:test",
                    "pe.axiz.cards.card-blocked.v1",
                    java.time.Instant.EPOCH,
                    null,
                    "application/json",
                    new byte[0],
                    java.util.Map.of())));
  }
}
