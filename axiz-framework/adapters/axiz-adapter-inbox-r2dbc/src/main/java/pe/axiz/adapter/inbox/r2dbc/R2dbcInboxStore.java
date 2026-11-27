package pe.axiz.adapter.inbox.r2dbc;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.kit.r2dbc.ReactiveSql;
import reactor.core.publisher.Mono;

/**
 * Frontera síncrona acotada para los hilos worker dedicados de sondeo y purga. Los flujos reactivos
 * de la aplicación usan {@link ReactiveInboxStore}; las reglas de SQL y de propiedad son
 * compartidas.
 */
public final class R2dbcInboxStore implements InboxStore {
  private final ReactiveInboxStore nativeStore;
  private final Duration timeout;

  public R2dbcInboxStore(ReactiveSql sql, String table, Duration timeout) {
    this.nativeStore = new R2dbcReactiveInboxStore(sql, table);
    this.timeout = Objects.requireNonNull(timeout);
    if (!timeout.isPositive()) throw new IllegalArgumentException("timeout must be positive");
  }

  @Override
  public Acquisition tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt) {
    return await(nativeStore.tryAcquire(consumer, messageId, owner, now, leaseUntil, expiresAt));
  }

  @Override
  public boolean markProcessed(String consumer, String messageId, String owner, Instant at) {
    return await(nativeStore.markProcessed(consumer, messageId, owner, at));
  }

  @Override
  public boolean release(String consumer, String messageId, String owner) {
    return await(nativeStore.release(consumer, messageId, owner));
  }

  @Override
  public int purgeExpired(Instant now) {
    return await(nativeStore.purgeExpired(now));
  }

  @Override
  public void verifySchema() {
    nativeStore.verifySchema().block(timeout);
  }

  private <T> T await(Mono<T> work) {
    try {
      return Objects.requireNonNull(work.block(timeout), "Inbox statement returned no value");
    } catch (InboxException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new InboxException("Inbox R2DBC statement failed: " + failure.getMessage(), failure);
    }
  }
}
