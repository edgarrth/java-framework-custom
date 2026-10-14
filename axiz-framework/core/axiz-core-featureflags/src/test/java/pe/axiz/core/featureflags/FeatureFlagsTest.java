package pe.axiz.core.featureflags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FeatureFlagsTest {

  private final FeatureFlags flags =
      new FeatureFlags(
          new MapFlagProvider(
              List.of(
                  FlagDefinition.on("new-checkout"),
                  FlagDefinition.off("legacy"),
                  new FlagDefinition(
                      "mobile-only", true, 100, Set.of("mobile"), Set.of(), Map.of(), "v2"),
                  new FlagDefinition(
                      "tenant-a",
                      true,
                      100,
                      Set.of(),
                      Set.of("tenant-a"),
                      Map.of("segment", Set.of("premium")),
                      null),
                  new FlagDefinition("canary", true, 20, Set.of(), Set.of(), Map.of(), null))));

  @Test
  void basicRules() {
    assertTrue(flags.isEnabled("new-checkout", EvaluationContext.empty()));
    assertFalse(flags.isEnabled("legacy", EvaluationContext.empty()));
    assertFalse(flags.isEnabled("unknown", EvaluationContext.empty()));
    assertTrue(
        flags.isEnabled("mobile-only", new EvaluationContext("u", "mobile", null, Map.of())));
    assertFalse(flags.isEnabled("mobile-only", new EvaluationContext("u", "web", null, Map.of())));
    assertEquals(
        "v2",
        flags.variant("mobile-only", new EvaluationContext("u", "mobile", null, Map.of()), "v1"));
    assertEquals("v1", flags.variant("mobile-only", EvaluationContext.empty(), "v1"));
    assertEquals("d", flags.variant("new-checkout", EvaluationContext.empty(), "d"));
    assertTrue(
        flags.isEnabled(
            "tenant-a",
            new EvaluationContext("u", null, "tenant-a", Map.of("segment", "premium"))));
    assertFalse(
        flags.isEnabled(
            "tenant-a", new EvaluationContext("u", null, "tenant-a", Map.of("segment", "basic"))));
    assertFalse(
        flags.isEnabled(
            "tenant-a",
            new EvaluationContext("u", null, "tenant-b", Map.of("segment", "premium"))));
  }

  @Test
  void percentageRolloutIsStableAndProportional() {
    long on =
        IntStream.range(0, 10_000)
            .filter(i -> flags.isEnabled("canary", EvaluationContext.of("user-" + i)))
            .count();
    assertTrue(on > 1700 && on < 2300, "on=" + on);
    for (int i = 0; i < 100; i++) {
      assertEquals(
          flags.isEnabled("canary", EvaluationContext.of("u" + i)),
          flags.isEnabled("canary", EvaluationContext.of("u" + i)));
    }
    assertFalse(
        flags.isEnabled("canary", EvaluationContext.empty()),
        "no targeting key: off for partial rollouts");
    assertThrows(
        IllegalArgumentException.class,
        () -> new FlagDefinition("x", true, 101, Set.of(), Set.of(), Map.of(), null));
  }
}
