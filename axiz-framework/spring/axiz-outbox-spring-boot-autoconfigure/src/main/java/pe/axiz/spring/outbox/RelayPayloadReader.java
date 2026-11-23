package pe.axiz.spring.outbox;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.PayloadProtector;

/**
 * Puente de solo lectura para el relay en segundo plano explícitamente imperativo; nunca una ruta
 * de solicitud reactiva.
 */
final class RelayPayloadReader implements PayloadProtector {
  private final AsyncPayloadProtector delegate;
  private final Duration timeout;

  RelayPayloadReader(AsyncPayloadProtector delegate, Duration timeout) {
    this.delegate = delegate;
    this.timeout = timeout;
  }

  @Override
  public byte[] protect(byte[] plaintext, String context, String binding) {
    throw new UnsupportedOperationException("Relay payload bridge is read-only");
  }

  @Override
  public byte[] reveal(byte[] stored, String context, String binding) {
    if (!Thread.currentThread().isVirtual())
      throw new IllegalStateException("Native payload relay requires its dedicated virtual thread");
    var pending = delegate.reveal(stored, context, binding).toCompletableFuture();
    boolean delivered = false;
    try {
      byte[] value = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      delivered = true;
      return value;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CryptoException("Relay payload read interrupted");
    } catch (TimeoutException | ExecutionException failure) {
      throw new CryptoException("Relay payload read unavailable");
    } finally {
      if (!delivered
          && !pending.cancel(true)
          && !pending.isCompletedExceptionally()
          && !pending.isCancelled()) {
        byte[] abandoned = pending.getNow(null);
        if (abandoned != null) Arrays.fill(abandoned, (byte) 0);
      }
    }
  }
}
