package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.messaging.CompletesInTransaction;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.spring.foundation.InboxCompletion;
import pe.axiz.spring.foundation.ReactiveInboxCompletion;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;

/**
 * Tests selection, failure and ownership boundaries; PostgreSQL proves the actual data atomicity.
 */
@ExtendWith(BlockHoundExtension.class)
class TransactionalHandlerResourceTest {
  private static final EventEnvelope EVENT =
      new EventEnvelope(
          "event",
          "urn:test",
          "test.created",
          Instant.EPOCH,
          null,
          "application/json",
          new byte[0],
          Map.of());

  private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
    var beans = new StaticListableBeanFactory();
    if (value != null) beans.addBean("value", value);
    return beans.getBeanProvider(type);
  }

  private EventHandler<String> handler(Runnable work) {
    return new EventHandler<>() {
      @Override
      public String eventType() {
        return "test.created";
      }

      @Override
      public Class<String> payloadType() {
        return String.class;
      }

      @Override
      public void handle(String payload, EventEnvelope event) {
        work.run();
      }
    };
  }

  private ResourceTransactionManager transactions(Object resource) {
    var manager = mock(ResourceTransactionManager.class);
    when(manager.getResourceFactory()).thenReturn(resource);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    return manager;
  }

  private InboxCompletion completion(Object resource, BooleanSupplier complete) {
    return new InboxCompletion() {
      @Override
      public Object transactionResource() {
        return resource;
      }

      @Override
      public boolean complete(String consumer, String key, String owner) {
        assertEquals("consumer", consumer);
        assertEquals("key", key);
        assertEquals("owner", owner);
        return complete.getAsBoolean();
      }
    };
  }

  private TransactionalEventHandlerPostProcessor jdbc(
      PlatformTransactionManager manager, InboxCompletion completion, String mode) {
    return new TransactionalEventHandlerPostProcessor(
        provider(PlatformTransactionManager.class, manager),
        provider(InboxCompletion.class, completion),
        mode);
  }

  @Test
  void jdbcCompletionRunsBetweenBusinessAndCommitAndARejectedCompletionRollsBack()
      throws Exception {
    Object resource = new Object();
    var manager = transactions(resource);
    List<String> order = new ArrayList<>();
    doAnswer(
            call -> {
              order.add("commit");
              return null;
            })
        .when(manager)
        .commit(any());
    var completion =
        completion(
            resource,
            () -> {
              order.add("inbox");
              return true;
            });
    var decorated =
        (CompletesInTransaction<String>)
            jdbc(manager, completion, "true")
                .postProcessAfterInitialization(handler(() -> order.add("business")), "handler");
    assertTrue(decorated.completesInTransaction());
    decorated.handleClaimed("payload", EVENT, "consumer", "key", "owner");
    assertEquals(List.of("business", "inbox", "commit"), order);
    var rejected =
        (CompletesInTransaction<String>)
            jdbc(manager, completion(resource, () -> false), "true")
                .postProcessAfterInitialization(handler(() -> {}), "handler");
    assertThrows(
        IllegalStateException.class,
        () -> rejected.handleClaimed("payload", EVENT, "consumer", "key", "owner"));
    verify(manager).rollback(any());
  }

  @Test
  void strictModeRejectsDistinctOrUnknownJdbcResourcesAndAutoKeepsTheLeaseFallback() {
    Object resource = new Object();
    var handler = handler(() -> {});
    var foreign = completion(new Object(), () -> true);
    assertThrows(
        IllegalStateException.class,
        () ->
            jdbc(transactions(resource), foreign, "true")
                .postProcessAfterInitialization(handler, "handler"));
    var fallback =
        (CompletesInTransaction<?>)
            jdbc(transactions(resource), foreign, "auto")
                .postProcessAfterInitialization(handler, "handler");
    assertFalse(fallback.completesInTransaction());
    var disabled =
        (CompletesInTransaction<?>)
            jdbc(transactions(resource), completion(resource, () -> true), "false")
                .postProcessAfterInitialization(handler, "handler");
    assertFalse(disabled.completesInTransaction());
    var unknown = mock(PlatformTransactionManager.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            jdbc(unknown, completion(resource, () -> true), "true")
                .postProcessAfterInitialization(handler, "handler"));
    assertSame(
        handler, jdbc(null, null, "auto").postProcessAfterInitialization(handler, "handler"));
  }

  @HandlesEvent("test.created")
  static class NativeHandler extends ReactiveTypedEventHandler<String> {
    @Override
    protected Mono<Void> on(String payload, EventEnvelope event) {
      return TransactionSynchronizationManager.forCurrentTransaction()
          .doOnNext(transaction -> assertTrue(transaction.isActualTransactionActive()))
          .then();
    }
  }

  private ReactiveInboxCompletion nativeCompletion(Object resource, boolean result) {
    return new ReactiveInboxCompletion() {
      @Override
      public Object transactionResource() {
        return resource;
      }

      @Override
      public Mono<Boolean> complete(String consumer, String key, String owner) {
        return Mono.just(result);
      }
    };
  }

  private ReactiveEventHandlerPostProcessor nativeProcessor(
      ReactiveTransactionManager manager, ReactiveInboxCompletion completion, String mode) {
    return new ReactiveEventHandlerPostProcessor(
        provider(ReactiveTransactionManager.class, manager),
        true,
        Duration.ofSeconds(5),
        provider(ReactiveInboxCompletion.class, completion),
        mode);
  }

  @Test
  void nativeCompletionFailureRollsBackAndTheJdbcProcessorNeverWrapsItAgain() {
    var database = new FakeR2dbc();
    var manager = new R2dbcTransactionManager(database);
    var handler =
        (CompletesInTransaction<String>)
            nativeProcessor(manager, nativeCompletion(database, false), "true")
                .postProcessAfterInitialization(new NativeHandler(), "native");
    assertTrue(handler.completesInTransaction());
    assertThrows(
        IllegalStateException.class,
        () -> handler.handleClaimed("payload", EVENT, "consumer", "key", "owner"));
    assertTrue(database.events().stream().anyMatch(event -> event.startsWith("rollback:")));
    assertFalse(database.events().stream().anyMatch(event -> event.startsWith("commit:")));
    assertSame(handler, jdbc(null, null, "true").postProcessAfterInitialization(handler, "native"));
  }

  @Test
  void strictNativeModeRejectsDifferentResourcesWhileAutoDisablesAtomicCompletion() {
    var database = new FakeR2dbc();
    var manager = new R2dbcTransactionManager(database);
    var completion = nativeCompletion(new FakeR2dbc(), true);
    assertThrows(
        IllegalStateException.class,
        () ->
            nativeProcessor(manager, completion, "true")
                .postProcessAfterInitialization(new NativeHandler(), "native"));
    var fallback =
        (CompletesInTransaction<?>)
            nativeProcessor(manager, completion, "auto")
                .postProcessAfterInitialization(new NativeHandler(), "native");
    assertFalse(fallback.completesInTransaction());
    assertSame(
        "plain bean",
        nativeProcessor(null, null, "auto").postProcessAfterInitialization("plain bean", "plain"));
  }

  @Test
  void invalidModesAndCompletionWithoutATransactionAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> jdbc(null, null, "maybe"));
    assertThrows(IllegalArgumentException.class, () -> nativeProcessor(null, null, "maybe"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReactiveTransactionalEventHandler<>(
                new NativeHandler(),
                null,
                Duration.ofSeconds(1),
                nativeCompletion(new Object(), true)));
    var manager = transactions(new Object());
    var handler =
        new TransactionalEventHandler<>(handler(() -> {}), new TransactionTemplate(manager));
    assertFalse(handler.completesInTransaction());
    assertThrows(
        IllegalStateException.class,
        () -> handler.handleClaimed("payload", EVENT, "consumer", "key", "owner"));
  }
}
