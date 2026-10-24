package pe.axiz.core.security;

import java.time.Duration;

/**
 * Remembers used nonces so a signed message is accepted only once. Implementations must be atomic
 * across every instance of the receiver (for example Redis {@code SET NX PX}); an in-memory store
 * only protects a single instance.
 */
public interface NonceStore {

  /**
   * Records the nonce if it was not seen in the last {@code ttl}.
   *
   * @return {@code true} the first time, {@code false} for a repeated nonce
   * @throws RuntimeException if the store cannot answer; callers must then reject the message
   */
  boolean markUsed(String nonce, Duration ttl);
}
