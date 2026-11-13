package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuditShutdownMonitorTest {
  @Test
  void anExternalObjectMonitorCannotBlockTheOwnedShutdown() throws Exception {
    var publisher =
        new AsyncAuditPublisher(
            event -> {},
            event -> {},
            2,
            Duration.ZERO,
            Duration.ofMillis(200),
            Duration.ofMillis(100),
            () -> () -> () -> {});
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        tasks.submit(
            () -> {
              synchronized (publisher) {
                held.countDown();
                try {
                  release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              }
            });
        assertTrue(held.await(2, TimeUnit.SECONDS));
        var stopped = tasks.submit(publisher::shutdown).get(2, TimeUnit.SECONDS);
        assertTrue(stopped.terminated());
      } finally {
        release.countDown();
      }
    } finally {
      publisher.close();
    }
  }
}
