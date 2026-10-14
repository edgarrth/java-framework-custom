package pe.axiz.framework.hexagonal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import pe.axiz.core.domain.AggregateRoot;
import pe.axiz.core.domain.DomainEvent;

class UseCaseRunnerTest {

  record PaymentCreated(String eventId, Instant occurredAt, String aggregateId)
      implements DomainEvent {}

  static final class Payment extends AggregateRoot<String> {
    private final String id;

    Payment(String id) {
      this.id = id;
      registerEvent(new PaymentCreated("e-" + id, Instant.EPOCH, id));
    }

    @Override
    public String id() {
      return id;
    }
  }

  static final class RecordingTx implements UseCaseTransaction {
    final List<String> log = new ArrayList<>();

    @Override
    public <T> T execute(boolean readOnly, Supplier<T> work) {
      log.add("begin");
      T r = work.get();
      log.add("commit");
      return r;
    }
  }

  @Test
  void eventsArePublishedAfterCommitAndDroppedOnFailure() {
    RecordingTx tx = new RecordingTx();
    List<String> published = new ArrayList<>();
    UseCaseRunner runner =
        new UseCaseRunner(
            tx,
            events -> {
              tx.log.add("publish");
              events.forEach(e -> published.add(e.eventId()));
            });
    String id =
        runner.run(
            false,
            () -> {
              Payment p = new Payment("p1");
              DomainEvents.collect(p);
              return p.id();
            });
    assertEquals("p1", id);
    assertEquals(List.of("begin", "commit", "publish"), tx.log);
    assertEquals(List.of("e-p1"), published);
    assertThrows(
        IllegalStateException.class,
        () ->
            runner.run(
                false,
                () -> {
                  DomainEvents.collect(new Payment("p2"));
                  throw new IllegalStateException("boom");
                }));
    assertEquals(List.of("e-p1"), published);
    assertThrows(IllegalStateException.class, () -> DomainEvents.collect(new Payment("p3")));
  }

  @Test
  void transactionalSinkPublishesInsideAndNestedUseCasesJoin() {
    RecordingTx tx = new RecordingTx();
    DomainEventSink outbox =
        new DomainEventSink() {
          @Override
          public void publish(List<DomainEvent> events) {
            tx.log.add("outbox:" + events.size());
          }

          @Override
          public boolean transactional() {
            return true;
          }
        };
    UseCaseRunner runner = new UseCaseRunner(tx, outbox);
    runner.run(
        false,
        () -> {
          DomainEvents.collect(new Payment("a"));
          return runner.run(
              false,
              () -> {
                DomainEvents.collect(new Payment("b"));
                return null;
              });
        });
    assertTrue(tx.log.contains("outbox:2"));
    assertEquals(1, tx.log.stream().filter(s -> s.startsWith("outbox")).count());
  }
}
