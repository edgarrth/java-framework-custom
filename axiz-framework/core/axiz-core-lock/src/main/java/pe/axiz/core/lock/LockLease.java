package pe.axiz.core.lock;

/**
 * The lease held while a {@link LockTemplate} runs a piece of work.
 *
 * <p>A lease can be lost while the work runs: the store did not renew it in time (a pause, a
 * network partition) and another owner may already hold the resource. The template reports that as
 * a {@link LockLostException} when the work ends, but an exception at the end does not undo what
 * the work already did. Work with external effects (a debit, a transfer, a message) should call
 * {@link #ensureHeld()} right before each effect, and the resource itself should reject writes of
 * an old owner: a conditional write with {@link #token()}, a database transaction or a persisted
 * idempotency key.
 */
public interface LockLease {

  String resource();

  /** Token of this owner, unique per acquisition; use it in conditional writes (fencing). */
  String token();

  /** Whether the lease is still known to be held. */
  boolean held();

  /**
   * Fails fast when the lease was lost.
   *
   * @throws LockLostException when another owner may hold the resource
   */
  void ensureHeld();
}
