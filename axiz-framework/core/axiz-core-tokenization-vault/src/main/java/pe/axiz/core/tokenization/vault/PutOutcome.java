package pe.axiz.core.tokenization.vault;

import java.util.Objects;
import pe.axiz.core.tokenization.Token;

/** Only confirmed conditional conflicts are retryable as collisions. */
public sealed interface PutOutcome {
  /** Both token and index references committed atomically. */
  record Created() implements PutOutcome {}

  /** A confirmed unique-index winner; callers must strongly reread and validate it. */
  record FingerprintExists(Token token) implements PutOutcome {
    public FingerprintExists {
      Objects.requireNonNull(token);
    }
  }

  /** Random token already occupied; does not grant access to its association. */
  record TokenCollision() implements PutOutcome {}

  /** Durable key version changed before the transaction. */
  record StaleKeyState() implements PutOutcome {}
}
