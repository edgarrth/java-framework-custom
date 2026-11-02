package pe.axiz.core.sequence;

import java.util.concurrent.CompletionStage;

/**
 * Native asynchronous atomic sequence allocation. A successful endpoint must already be durable,
 * independent of the caller's business transaction. Cancelled reservations may leave gaps.
 */
@FunctionalInterface
public interface AsyncSequenceStore {
  CompletionStage<Long> increment(String name, String period, int increment);
}
