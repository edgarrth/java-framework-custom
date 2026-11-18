package pe.axiz.payments.notifications.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.lock.InMemoryDistributedLock;
import pe.axiz.core.lock.LockObserver;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.core.storage.InMemoryObjectStorage;
import pe.axiz.payments.notifications.event.AuditRecord;
import pe.axiz.payments.notifications.repository.archive.AuditArchive;

/** Audit events of the queue end up in the archive. */
class AuditArchiveHandlerTest {

  final InMemoryObjectStorage storage = new InMemoryObjectStorage();
  final AuditArchiveHandler handler =
      new AuditArchiveHandler(
          new AuditArchive(
              storage,
              new LockTemplate(
                  new InMemoryDistributedLock(Clock.systemUTC()),
                  Duration.ofMillis(2),
                  LockObserver.NONE)));

  @Test
  void handlesTheAuditEventsAndArchivesEachRecord() throws Exception {
    assertThat(handler.eventType()).isEqualTo("pe.axiz.audit.event.v1");
    assertThat(handler.payloadType()).isEqualTo(AuditRecord.class);

    handler.handle(
        new AuditRecord(
            "e-1",
            "2026-10-12T04:59:59Z",
            "banking-core",
            "account.debit",
            "account",
            null,
            "SUCCESS",
            Map.of("amount", "150.75")),
        null);

    assertThat(storage.head("audit/2026/10/12/banking-core/e-1.json")).isPresent();
  }
}
