package pe.axiz.core.lock;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** CPU-only single-process lease store, for local/test environments. */
public final class InMemoryAsyncDistributedLock implements AsyncDistributedLock {
  private final InMemoryDistributedLock locks;

  public InMemoryAsyncDistributedLock(Clock clock) {
    locks = new InMemoryDistributedLock(clock);
  }

  @Override
  public CompletionStage<Optional<AsyncLockHandle>> tryAcquire(String resource, Duration lease) {
    return CompletableFuture.completedFuture(
        locks
            .tryAcquire(resource, lease)
            .map(
                handle ->
                    new AsyncLockHandle() {
                      @Override
                      public String resource() {
                        return handle.resource();
                      }

                      @Override
                      public String token() {
                        return handle.token();
                      }

                      @Override
                      public CompletionStage<Boolean> renew(Duration value) {
                        return CompletableFuture.completedFuture(handle.renew(value));
                      }

                      @Override
                      public CompletionStage<Void> release() {
                        handle.release();
                        return CompletableFuture.completedFuture(null);
                      }
                    }));
  }
}
