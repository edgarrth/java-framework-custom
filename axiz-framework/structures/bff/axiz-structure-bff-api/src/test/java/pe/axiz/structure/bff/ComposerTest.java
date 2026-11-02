package pe.axiz.structure.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.ExternalServiceException;

class ComposerTest {

  @Test
  void optionalPartsDegradeAndRequiredPartsFail() {
    long start = System.nanoTime();
    Composition c =
        Composer.within(Duration.ofMillis(500))
            .required(
                "account",
                () -> {
                  Thread.sleep(100);
                  return "acc-1";
                })
            .required(
                "balance",
                () -> {
                  Thread.sleep(100);
                  return 150;
                })
            .optional(
                "offers",
                () -> {
                  Thread.sleep(5000);
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
                      Thread.sleep(2000);
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
