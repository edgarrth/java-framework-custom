package pe.axiz.test.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.inbox.InboxStore;

/**
 * Los contratos en sí: un almacén que rompe una garantía debe hacer fallar la verificación que la
 * describe; de lo contrario, una ejecución de contrato en verde no probaría nada.
 */
class ContractsCatchBrokenStoresTest {

  /** Un almacén correcto (JDBC sobre H2) con un comportamiento reemplazado. */
  abstract static class Broken implements InboxStore {
    final InboxStore delegate = JdbcInboxStoreContractTest.h2();

    @Override
    public Acquisition tryAcquire(
        String consumer,
        String messageId,
        String owner,
        Instant now,
        Instant leaseUntil,
        Instant expiresAt) {
      return delegate.tryAcquire(consumer, messageId, owner, now, leaseUntil, expiresAt);
    }

    @Override
    public boolean markProcessed(String consumer, String messageId, String owner, Instant at) {
      return delegate.markProcessed(consumer, messageId, owner, at);
    }

    @Override
    public boolean release(String consumer, String messageId, String owner) {
      return delegate.release(consumer, messageId, owner);
    }

    @Override
    public int purgeExpired(Instant now) {
      return delegate.purgeExpired(now);
    }
  }

  private static ContractCheck check(List<ContractCheck> checks, String name) {
    return checks.stream()
        .filter(c -> c.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no check named " + name));
  }

  @Test
  void aStoreThatLetsAnyDeliveryCompleteFailsTheOwnerCheck() {
    List<ContractCheck> checks =
        InboxStoreContract.checks(
            () ->
                new Broken() {
                  @Override
                  public boolean markProcessed(
                      String consumer, String messageId, String owner, Instant at) {
                    delegate.markProcessed(consumer, messageId, owner, at);
                    return true; // ignora al propietario
                  }
                });
    AssertionError e =
        assertThrows(AssertionError.class, check(checks, "only the owner completes")::run);
    assertFalse(e.getMessage().isBlank());
  }

  @Test
  void aStoreThatAcquiresEverythingFailsExclusivity() {
    List<ContractCheck> checks =
        InboxStoreContract.checks(
            () ->
                new Broken() {
                  @Override
                  public Acquisition tryAcquire(
                      String consumer,
                      String messageId,
                      String owner,
                      Instant now,
                      Instant leaseUntil,
                      Instant expiresAt) {
                    return Acquisition.acquired();
                  }
                });
    AssertionError e =
        assertThrows(AssertionError.class, check(checks, "one delivery at a time")::run);
    assertTrue(e.getMessage().contains("expected <"), e.getMessage());
  }

  @Test
  void aStoreThatNeverPurgesFailsRetentionUnlessTheServerPurges() {
    InboxStoreContract.Backend keepsEverything =
        () ->
            new Broken() {
              @Override
              public int purgeExpired(Instant now) {
                return 0;
              }
            };
    List<ContractCheck> checks = InboxStoreContract.checks(keepsEverything);
    assertThrows(
        AssertionError.class, check(checks, "processed entries are purged after retention")::run);

    InboxStoreContract.Backend serverTtl =
        new InboxStoreContract.Backend() {
          @Override
          public InboxStore create() {
            return keepsEverything.create();
          }

          @Override
          public boolean purgesOnDemand() {
            return false;
          }
        };
    List<ContractCheck> withoutPurge = InboxStoreContract.checks(serverTtl);
    assertEquals(checks.size() - 1, withoutPurge.size());
    assertTrue(
        withoutPurge.stream().noneMatch(c -> c.name().contains("purged")),
        "a store purged by the server (TTL) is not asked to purge");
  }

  @Test
  void expectationsReportWhatDiffered() {
    Expect.isTrue(true, "fine");
    Expect.isFalse(false, "fine");
    Expect.equal("a", "a", "fine");
    assertEquals(
        "flag",
        assertThrows(AssertionError.class, () -> Expect.isTrue(false, "flag")).getMessage());
    assertEquals(
        "flag",
        assertThrows(AssertionError.class, () -> Expect.isFalse(true, "flag")).getMessage());
    assertEquals(
        "status: expected <A> but was <B>",
        assertThrows(AssertionError.class, () -> Expect.equal("A", "B", "status")).getMessage());
  }
}
