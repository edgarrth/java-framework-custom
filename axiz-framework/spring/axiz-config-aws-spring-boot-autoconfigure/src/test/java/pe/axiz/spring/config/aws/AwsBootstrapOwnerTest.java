package pe.axiz.spring.config.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

class AwsBootstrapOwnerTest {
  @Test
  void bothCleanupFailuresPreserveTheClientFailureAndCloseIdentityOnlyOnce() {
    var client = new IllegalStateException("client close");
    var identity = new IllegalArgumentException("identity close");
    var closed = new AtomicInteger();
    var owner =
        new AwsBootstrapOwner(
            () ->
                new AwsBootstrapOwner.Lease(
                    new Object(),
                    () -> {
                      closed.incrementAndGet();
                      throw identity;
                    }));
    owner.provider();
    owner.ownsClient(
        () -> {
          throw client;
        });
    assertSame(client, assertThrows(IllegalStateException.class, owner::close));
    assertEquals(1, client.getSuppressed().length);
    assertSame(identity, client.getSuppressed()[0]);
    owner.close();
    assertEquals(1, closed.get());
  }

  @Test
  void ownsLazyResourcesAndClosesClientsBeforeIdentityExactlyOnceOnEarlyFailure() {
    var events = new ArrayList<String>();
    var created = new AtomicInteger();
    var provider = new Object();
    var owner =
        new AwsBootstrapOwner(
            () -> {
              created.incrementAndGet();
              return new AwsBootstrapOwner.Lease(provider, () -> events.add("identity"));
            });
    assertEquals(0, created.get());
    assertSame(provider, owner.provider());
    assertSame(provider, owner.provider());
    assertEquals(1, created.get());
    owner.ownsClient(() -> events.add("client"));
    owner.failed();
    owner.failed();
    owner.close();
    assertEquals(List.of("client", "identity"), events);
    assertThrows(IllegalStateException.class, owner::provider);
  }

  @Test
  void applicationOwnsTransferredResourcesAcrossIndependentReloadsAndRefreshFailure() {
    var closed = new AtomicInteger();
    for (int n = 0; n < 2; n++) {
      var context = new GenericApplicationContext();
      context.refresh();
      var owner =
          new AwsBootstrapOwner(
              () -> new AwsBootstrapOwner.Lease(new Object(), closed::incrementAndGet));
      owner.provider();
      owner.transfer(context);
      owner.failed();
      assertEquals(n, closed.get());
      context.close();
      owner.close();
      assertEquals(n + 1, closed.get());
    }
  }

  @Test
  void untouchedOrBorrowedBootstrapDoesNotCreateNorCloseAnyCredentialSource() {
    var created = new AtomicInteger();
    var owner =
        new AwsBootstrapOwner(
            () -> {
              created.incrementAndGet();
              return new AwsBootstrapOwner.Lease(new Object(), () -> {});
            });
    owner.close();
    assertEquals(0, created.get());
  }

  @Test
  void identityStillClosesWhenClientCleanupThrows() {
    var closed = new AtomicInteger();
    var owner =
        new AwsBootstrapOwner(
            () -> new AwsBootstrapOwner.Lease(new Object(), closed::incrementAndGet));
    owner.provider();
    owner.ownsClient(
        () -> {
          throw new IllegalStateException("client close");
        });
    assertThrows(IllegalStateException.class, owner::close);
    owner.close();
    assertEquals(1, closed.get());
  }
}
