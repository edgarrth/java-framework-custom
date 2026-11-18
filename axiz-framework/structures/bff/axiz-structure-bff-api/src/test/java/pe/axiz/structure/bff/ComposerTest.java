package pe.axiz.structure.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.ExternalServiceException;

class ComposerTest {

  @Test
  void successfulCompositionPropagatesContextAndNeverReportsDegradation() {
    AtomicInteger calls = new AtomicInteger();
    Composition complete =
        Composer.within(
                Duration.ofSeconds(2),
                task ->
                    () -> {
                      calls.incrementAndGet();
                      return task.call();
                    })
            .required("value", () -> "accepted")
            .run();
    assertEquals("accepted", complete.get("value", String.class));
    assertEquals(1, calls.get());
    assertFalse(complete.partial());
    assertEquals(List.of(), complete.degraded());
  }

  @Test
  void optionalPartsDegradeAndRequiredPartsFail() {
    long start = System.nanoTime();
    CyclicBarrier together = new CyclicBarrier(2);
    Composition c =
        Composer.within(Duration.ofMillis(500))
            .required(
                "account",
                () -> {
                  together.await(2, TimeUnit.SECONDS);
                  return "acc-1";
                })
            .required(
                "balance",
                () -> {
                  together.await(2, TimeUnit.SECONDS);
                  return 150;
                })
            .optional(
                "offers",
                () -> {
                  new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                  return List.of("x");
                },
                List::of)
            .run();
    assertTrue(
        Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000, "parts run in parallel");
    assertEquals("acc-1", c.get("account", String.class));
    assertEquals(150, c.get("balance", Integer.class));
    assertEquals(List.of("offers"), c.degraded());
    assertTrue(c.partial());

    assertThrows(
        IllegalStateException.class,
        () ->
            Composer.within(Duration.ofSeconds(1))
                .required(
                    "a",
                    () -> {
                      throw new IllegalStateException("down");
                    })
                .run());
    assertThrows(
        ExternalServiceException.class,
        () ->
            Composer.within(Duration.ofMillis(50))
                .required(
                    "slow",
                    () -> {
                      new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                      return 1;
                    })
                .run());
  }

  @Test
  void adapterPerChannel() {
    record A(String channel, String prefix) implements ChannelAdapter<String, String> {
      @Override
      public String adapt(String source) {
        return prefix + source;
      }
    }
    ChannelAdapters<String, String> adapters =
        new ChannelAdapters<>(List.of(new A("mobile", "m:"), new A("*", "d:")));
    assertEquals("m:x", adapters.adapt("x", "mobile"));
    assertEquals("d:x", adapters.adapt("x", "web"));
  }
}
