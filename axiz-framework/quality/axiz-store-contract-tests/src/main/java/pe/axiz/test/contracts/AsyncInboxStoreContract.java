package pe.axiz.test.contracts;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore;

/**
 * Reutiliza el contrato de propiedad y expiración para los almacenes nativos. El driver de JUnit
 * espera con un plazo; los envíos se ejecutan en el executor no bloqueante provisto para que
 * BlockHound verifique toda la ruta de E/S.
 */
public final class AsyncInboxStoreContract {
  /**
   * Backend aislado; los almacenes con TTL gestionado por el servidor quedan excluidos de las
   * verificaciones de purga explícita.
   */
  public interface Backend {
    AsyncInboxStore create();

    default boolean purgesOnDemand() {
      return true;
    }

    default Instant start() {
      return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
  }

  private AsyncInboxStoreContract() {}

  public static List<ContractCheck> checks(Backend backend, Executor nonBlockingExecutor) {
    Objects.requireNonNull(backend);
    Objects.requireNonNull(nonBlockingExecutor);
    return InboxStoreContract.checks(
        new InboxStoreContract.Backend() {
          @Override
          public InboxStore create() {
            return new Driver(backend.create(), nonBlockingExecutor);
          }

          @Override
          public boolean purgesOnDemand() {
            return backend.purgesOnDemand();
          }

          @Override
          public Instant start() {
            return backend.start();
          }
        });
  }

  private record Driver(AsyncInboxStore store, Executor executor) implements InboxStore {
    @Override
    public Acquisition tryAcquire(
        String consumer,
        String messageId,
        String owner,
        Instant now,
        Instant leaseUntil,
        Instant expiresAt) {
      return await(() -> store.tryAcquire(consumer, messageId, owner, now, leaseUntil, expiresAt));
    }

    @Override
    public boolean markProcessed(String consumer, String messageId, String owner, Instant at) {
      return await(() -> store.markProcessed(consumer, messageId, owner, at));
    }

    @Override
    public boolean release(String consumer, String messageId, String owner) {
      return await(() -> store.release(consumer, messageId, owner));
    }

    @Override
    public int purgeExpired(Instant now) {
      return await(() -> store.purgeExpired(now));
    }

    private <T> T await(Supplier<CompletionStage<T>> work) {
      var request =
          Stages.compose(CompletableFuture.supplyAsync(work, executor), stage -> stage)
              .toCompletableFuture();
      try {
        return Objects.requireNonNull(request.get(30, TimeUnit.SECONDS));
      } catch (InterruptedException failure) {
        request.cancel(true);
        Thread.currentThread().interrupt();
        throw new AssertionError("Async inbox contract interrupted", failure);
      } catch (ExecutionException | TimeoutException failure) {
        request.cancel(true);
        throw new AssertionError("Async inbox contract failed", failure);
      }
    }
  }
}
