package pe.axiz.core.security;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Atomic asynchronous replay protection. Store errors must fail authentication closed. */
@FunctionalInterface
public interface AsyncNonceStore {
  CompletionStage<Boolean> markUsed(String nonce, Duration ttl);
}
