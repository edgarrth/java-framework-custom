package pe.axiz.payments.notifications.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.json.JsonReader;
import pe.axiz.core.lock.InMemoryDistributedLock;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.core.storage.InMemoryObjectStorage;
import pe.axiz.payments.notifications.event.AuditRecord;

class AuditArchiveTest {

  final InMemoryObjectStorage storage = new InMemoryObjectStorage();
  final AuditArchive archive =
      new AuditArchive(
          storage,
          new LockTemplate(new InMemoryDistributedLock(Clock.systemUTC()), Duration.ofMillis(2)));

  static AuditRecord record(String id, String service) {
    return new AuditRecord(
        id,
        "2026-10-12T04:59:59Z",
        service,
        "account.debit",
        "account",
        null,
        "SUCCESS",
        Map.of("amount", "150.75"));
  }

  Map<String, Object> manifest() throws Exception {
    try (var in = storage.get("audit/2026/10/12/_manifest.json").orElseThrow().content()) {
      return JsonReader.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void storesOneObjectPerRecordUnderItsUtcDay() {
    String key = archive.store(record("a/../1", "banking-core"));

    assertThat(key).isEqualTo("audit/2026/10/12/banking-core/a_.._1.json");
    assertThat(storage.head(key)).isPresent();
  }

  @Test
  void theManifestCountsEachRecordOnceEvenWithConcurrentReplicas() throws Exception {
    var pool = Executors.newFixedThreadPool(8);
    for (int i = 0; i < 40; i++) {
      AuditRecord r = record("e-" + i, i % 2 == 0 ? "a" : "b");
      pool.submit(() -> archive.store(r));
    }
    pool.shutdown();
    assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    archive.store(record("e-0", "a"));

    assertThat(manifest()).containsEntry("a", new BigDecimal(20));
    assertThat(manifest()).containsEntry("b", new BigDecimal(20));
  }
}
