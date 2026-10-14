package pe.axiz.core.messaging;

/** What a consumer does with a message after processing it. */
public enum DeliveryDecision {
  /** Processed (or already processed by an earlier delivery): acknowledge. */
  ACK,
  /** Transient failure: redeliver after backoff. */
  RETRY,
  /**
   * Another delivery holds the message (valid inbox lease): redeliver once that lease may have
   * ended, without counting it as a failed attempt. Never acknowledged, so a delivery that died
   * holding the lease does not lose the message.
   */
  DEFER,
  /** Permanent failure or attempts exhausted: send to the dead-letter destination. */
  DEAD_LETTER
}
