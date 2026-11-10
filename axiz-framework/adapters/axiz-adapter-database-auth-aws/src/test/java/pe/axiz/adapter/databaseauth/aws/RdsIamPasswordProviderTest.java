package pe.axiz.adapter.databaseauth.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.test.support.MutableClock;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

class RdsIamPasswordProviderTest {

  private final List<GenerateAuthenticationTokenRequest> requests = new ArrayList<>();
  private final RdsUtilities rds =
      new RdsUtilities() {
        @Override
        public String generateAuthenticationToken(GenerateAuthenticationTokenRequest request) {
          requests.add(request);
          return "token-" + requests.size();
        }
      };

  @Test
  void reusesTheTokenUntilItMustBeRenewed() {
    MutableClock clock = MutableClock.at("2026-10-11T10:00:00Z");
    RdsIamPasswordProvider p =
        new RdsIamPasswordProvider(
            rds, "db.cluster.local", 5432, "app", clock, Duration.ofMinutes(10));

    assertEquals("token-1", p.password().toString());
    clock.advance(Duration.ofMinutes(9));
    assertEquals("token-1", p.password().toString());
    clock.advance(Duration.ofMinutes(1));
    assertEquals("token-2", p.password().toString());

    GenerateAuthenticationTokenRequest r = requests.get(0);
    assertEquals("db.cluster.local", r.hostname());
    assertEquals(5432, r.port());
    assertEquals("app", r.username());
    assertTrue(!p.toString().contains("token"), "never prints the token");
  }

  @Test
  void refreshMustHappenBeforeTheTokenExpires() {
    MutableClock clock = MutableClock.at("1970-01-01T00:00:00Z");
    assertThrows(
        IllegalArgumentException.class,
        () -> new RdsIamPasswordProvider(rds, "h", 5432, "u", clock, Duration.ofMinutes(15)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RdsIamPasswordProvider(rds, "h", 5432, "u", clock, Duration.ofNanos(-1)));
  }

  @Test
  void zeroRefreshAlwaysSignsAgainAndOwnedResourcesAreClosed() {
    var closed = new AtomicInteger();
    var provider =
        new RdsIamPasswordProvider(
            rds,
            "db",
            5432,
            "app",
            MutableClock.at("2026-11-08T00:00:00Z"),
            Duration.ZERO,
            closed::incrementAndGet);
    assertEquals("token-1", provider.password());
    assertEquals("token-2", provider.password());
    assertEquals(0, closed.get());
    provider.close();
    assertEquals(1, closed.get());
    new RdsIamPasswordProvider(
            rds, "db", 5432, "app", MutableClock.at("2026-11-08T00:00:00Z"), Duration.ZERO)
        .close();
  }

  @Test
  void aSigningFailureDoesNotCacheAnInvalidToken() {
    var calls = new AtomicInteger();
    RdsUtilities flaky =
        new RdsUtilities() {
          @Override
          public String generateAuthenticationToken(GenerateAuthenticationTokenRequest request) {
            if (calls.incrementAndGet() == 1)
              throw new IllegalStateException("credentials expired");
            return "valid-token";
          }
        };
    var provider =
        new RdsIamPasswordProvider(
            flaky,
            "db",
            5432,
            "app",
            MutableClock.at("2026-11-08T00:00:00Z"),
            Duration.ofMinutes(10));
    assertThrows(IllegalStateException.class, provider::password);
    assertEquals("valid-token", provider.password());
    assertEquals("valid-token", provider.password());
    assertEquals(2, calls.get());
  }

  @Test
  void concurrentInitialConnectionsObserveTheSameWinningCachedToken() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    RdsUtilities concurrent =
        new RdsUtilities() {
          @Override
          public String generateAuthenticationToken(GenerateAuthenticationTokenRequest request) {
            int token = calls.incrementAndGet();
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
            return "token-" + token;
          }
        };
    var provider =
        new RdsIamPasswordProvider(
            concurrent,
            "db",
            5432,
            "app",
            MutableClock.at("2026-11-08T00:00:00Z"),
            Duration.ofMinutes(10));
    var workers = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var first = workers.submit(provider::password);
      var second = workers.submit(provider::password);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      release.countDown();
      var winner = first.get(5, TimeUnit.SECONDS);
      assertEquals(winner, second.get(5, TimeUnit.SECONDS));
      assertEquals(winner, provider.password());
      assertEquals(2, calls.get());
    } finally {
      release.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
      provider.close();
    }
  }
}
