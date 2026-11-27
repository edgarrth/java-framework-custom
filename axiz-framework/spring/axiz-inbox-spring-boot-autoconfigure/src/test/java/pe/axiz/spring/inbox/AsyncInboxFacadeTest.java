package pe.axiz.spring.inbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import reactor.test.StepVerifier;

class AsyncInboxFacadeTest {
  @Test
  void acquisitionIsLazyAndARejectedOwnerCannotConfirmProcessing() {
    var now = Instant.parse("2026-11-19T12:00:00Z");
    var lease = now.plusSeconds(60);
    var expires = now.plusSeconds(3600);
    var acquired = new CompletableFuture<Acquisition>();
    var store = mock(AsyncInboxStore.class);
    when(store.tryAcquire("consumer", "message", "owner", now, lease, expires))
        .thenReturn(acquired);
    when(store.markProcessed("consumer", "message", "owner", now))
        .thenReturn(CompletableFuture.completedFuture(false));
    var facade = new AsyncInboxFacade(store);
    var attempt = facade.tryAcquire("consumer", "message", "owner", now, lease, expires);
    verifyNoInteractions(store);
    var expected = Acquisition.acquired();
    StepVerifier.create(attempt)
        .then(() -> acquired.complete(expected))
        .expectNext(expected)
        .verifyComplete();
    StepVerifier.create(facade.markProcessed("consumer", "message", "owner", now))
        .expectNext(false)
        .verifyComplete();
  }

  @Test
  void maintenancePreservesProviderFailuresInsteadOfReportingSuccess() {
    var now = Instant.parse("2026-11-19T12:00:00Z");
    var failure = new IllegalStateException("Synthetic store unavailable");
    var store = mock(AsyncInboxStore.class);
    when(store.purgeExpired(now)).thenReturn(CompletableFuture.failedFuture(failure));
    when(store.verifySchema()).thenThrow(failure);
    var facade = new AsyncInboxFacade(store);
    StepVerifier.create(facade.purgeExpired(now))
        .expectErrorMatches(actual -> actual == failure)
        .verify();
    StepVerifier.create(facade.verifySchema())
        .expectErrorMatches(actual -> actual == failure)
        .verify();
  }
}
