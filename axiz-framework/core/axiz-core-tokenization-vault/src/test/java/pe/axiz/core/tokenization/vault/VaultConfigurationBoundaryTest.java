package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.TextTokenProfiles;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;

class VaultConfigurationBoundaryTest {
  @Test
  void budgetsBoundEachDimensionAndRequireMonotonicBurstMinuteAndDayWindows() {
    for (var window :
        List.of(
            new TokenBudgetPolicy.Windows(1, 1, 1),
            new TokenBudgetPolicy.Windows(1, 2, 1_000_000_000L))) {
      var limits = new TokenBudgetPolicy.Limits(window, window, window);
      for (int concurrent : List.of(1, 1_000_000)) {
        for (Duration retry : List.of(Duration.ofSeconds(1), Duration.ofMinutes(1))) {
          var policy =
              budget(limits)
                  .subjectConcurrent(concurrent)
                  .clientConcurrent(concurrent)
                  .tenantConcurrent(concurrent)
                  .concurrencyRetryAfter(retry)
                  .build();
          assertEquals(concurrent, policy.subjectConcurrent());
          assertEquals(concurrent, policy.clientConcurrent());
          assertEquals(concurrent, policy.tenantConcurrent());
          assertEquals(retry, policy.concurrencyRetryAfter());
          assertEquals(limits, policy.query());
          assertEquals(limits, policy.reveal());
          assertEquals(limits, policy.metadata());
          assertEquals(limits, policy.writes());
        }
      }
      for (int invalid : List.of(0, 1_000_001)) {
        assertThrows(
            IllegalArgumentException.class,
            () -> budget(limits).subjectConcurrent(invalid).build());
        assertThrows(
            IllegalArgumentException.class, () -> budget(limits).clientConcurrent(invalid).build());
        assertThrows(
            IllegalArgumentException.class, () -> budget(limits).tenantConcurrent(invalid).build());
      }
      for (Duration retry : List.of(Duration.ofMillis(999), Duration.ofSeconds(60).plusNanos(1)))
        assertThrows(
            IllegalArgumentException.class,
            () -> budget(limits).concurrencyRetryAfter(retry).build());
    }
    for (long[] bad :
        List.of(
            new long[] {0, 1, 1},
            new long[] {2, 1, 2},
            new long[] {1, 2, 1},
            new long[] {1, 2, 1_000_000_001L}))
      assertThrows(
          IllegalArgumentException.class,
          () -> new TokenBudgetPolicy.Windows(bad[0], bad[1], bad[2]));
  }

  private static TokenBudgetPolicy.Builder budget(TokenBudgetPolicy.Limits limits) {
    return TokenBudgetPolicy.builder()
        .query(limits)
        .reveal(limits)
        .metadata(limits)
        .writes(limits)
        .subjectConcurrent(1)
        .clientConcurrent(1)
        .tenantConcurrent(1)
        .concurrencyRetryAfter(Duration.ofSeconds(1));
  }

  @Test
  void maintenanceProgressIsBoundedAndNeverPrintsAnOpaqueCursor() {
    String cursor = "private-cursor".repeat(73);
    for (String accepted : List.of("x", cursor, "x".repeat(1024))) {
      var progress = new VaultMaintenanceBatch(100, 50, 50, accepted);
      var refs = new VaultReferencePage(100, accepted);
      var page = new VaultScanPage(List.of(), accepted);
      assertEquals(accepted, progress.nextCursor());
      assertEquals(accepted, refs.nextCursor());
      assertEquals(accepted, page.nextCursor());
      assertFalse(progress.toString().contains(accepted));
      assertFalse(refs.toString().contains(accepted));
      assertFalse(page.toString().contains(accepted));
    }
    assertEquals(0, new VaultMaintenanceBatch(0, 0, 0, null).inspected());
    assertThrows(
        IllegalArgumentException.class,
        () -> new VaultMaintenanceBatch(0, Integer.MAX_VALUE, Integer.MAX_VALUE, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VaultRetentionBatch(0, Integer.MAX_VALUE, Integer.MAX_VALUE, 2, 0, null));
    for (int[] bad :
        List.of(
            new int[] {-1, 0, 0},
            new int[] {101, 0, 0},
            new int[] {1, -1, 0},
            new int[] {1, 0, -1},
            new int[] {1, 1, 1}))
      assertThrows(
          IllegalArgumentException.class,
          () -> new VaultMaintenanceBatch(bad[0], bad[1], bad[2], null));
    for (int bad : List.of(-1, 101))
      assertThrows(IllegalArgumentException.class, () -> new VaultReferencePage(bad, null));
    for (String bad : List.of("", "x".repeat(1025))) {
      assertThrows(IllegalArgumentException.class, () -> new VaultMaintenanceBatch(0, 0, 0, bad));
      assertThrows(IllegalArgumentException.class, () -> new VaultReferencePage(0, bad));
      assertThrows(IllegalArgumentException.class, () -> new VaultScanPage(List.of(), bad));
      assertThrows(
          IllegalArgumentException.class, () -> new VaultRetentionBatch(0, 0, 0, 0, 0, bad));
    }
    var source = new ArrayList<VaultRecord>();
    var copy = new VaultScanPage(source, null);
    assertThrows(UnsupportedOperationException.class, () -> copy.records().clear());
    assertEquals(0, new VaultReferencePage(0, null).references());
  }

  private static TokenTypePolicy.Builder typePolicy() {
    return TokenTypePolicy.builder()
        .type(new TokenType("DNI"))
        .profile(TextTokenProfiles.dniPe())
        .keysetId("main")
        .domains(Set.of(new TokenDomain("payments")))
        .purposes(Set.of("support"))
        .retention(Duration.ofDays(1))
        .metadataKeys(Set.of());
  }

  @Test
  void typePoliciesBoundRetentionDimensionsAndForbidSensitiveAuthenticationData() {
    var invalid = new ArrayList<Consumer<TokenTypePolicy.Builder>>();
    invalid.add(b -> b.domains(Set.of()));
    invalid.add(b -> b.purposes(Set.of()));
    for (Duration retention :
        List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofDays(3650).plusNanos(1)))
      invalid.add(b -> b.retention(retention));
    var domains = new HashSet<TokenDomain>();
    var purposes = new HashSet<String>();
    var metadata = new HashSet<String>();
    for (int i = 0; i < 65; i++) domains.add(new TokenDomain("domain-" + i));
    for (int i = 0; i < 33; i++) purposes.add("purpose-" + i);
    for (int i = 0; i < 17; i++) metadata.add("key-" + i);
    invalid.add(b -> b.domains(domains));
    invalid.add(b -> b.purposes(purposes));
    invalid.add(b -> b.metadataKeys(metadata));
    invalid.add(b -> b.purposes(Set.of("invalid purpose")));
    invalid.add(b -> b.metadataKeys(Set.of("k".repeat(33))));
    for (String forbidden :
        List.of(
            "CVV",
            "CVV2",
            "CVC",
            "CVC2",
            "CID",
            "PIN",
            "PINBLOCK",
            "TRACK1",
            "TRACK2",
            "TRACKDATA")) invalid.add(b -> b.type(new TokenType(forbidden)));
    for (var configure : invalid) {
      var builder = typePolicy();
      configure.accept(builder);
      assertThrows(AxizException.class, builder::build);
    }
    domains.remove(new TokenDomain("domain-64"));
    purposes.remove("purpose-32");
    metadata.remove("key-16");
    var accepted =
        typePolicy()
            .domains(domains)
            .purposes(purposes)
            .metadataKeys(metadata)
            .retention(Duration.ofDays(3650))
            .build();
    domains.clear();
    purposes.clear();
    metadata.clear();
    assertEquals(64, accepted.domains().size());
    assertEquals(32, accepted.purposes().size());
    assertEquals(16, accepted.metadataKeys().size());
    assertThrows(UnsupportedOperationException.class, () -> accepted.domains().clear());
    assertEquals(
        Duration.ofNanos(1), typePolicy().retention(Duration.ofNanos(1)).build().retention());
    assertThrows(
        AxizException.class,
        () -> new TokenTypePolicy.PanHeuristicExemption("reason", "review", 0));
  }

  @Test
  void identifiersAndOperationIdsAcceptOnlyTheirCanonicalBoundedRepresentations() {
    assertEquals("a".repeat(64), VaultIdentifiers.identifier("a".repeat(64), 64));
    for (String bad : List.of("", "a".repeat(65), "\n", " leading", "_leading"))
      assertThrows(AxizException.class, () -> VaultIdentifiers.identifier(bad, 64));
    assertThrows(AxizException.class, () -> VaultIdentifiers.identifier(null, 64));
    assertEquals("a".repeat(32), VaultIdentifiers.key("a".repeat(32)));
    for (String bad : List.of("", "a".repeat(33), "key.id", "key/other"))
      assertThrows(AxizException.class, () -> VaultIdentifiers.key(bad));
    assertThrows(AxizException.class, () -> VaultIdentifiers.key(null));
    UUID id = UUID.randomUUID();
    assertEquals(id, VaultIdentifiers.operationId(id.toString()));
    for (String bad :
        List.of(
            "",
            "not-a-uuid".repeat(4),
            "0".repeat(36),
            "00000000-0000-1000-8000-000000000000",
            "00000000-0000-4000-0000-000000000000",
            "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))
      assertThrows(AxizException.class, () -> VaultIdentifiers.operationId(bad));
    assertThrows(AxizException.class, () -> VaultIdentifiers.operationId(null));
  }
}
