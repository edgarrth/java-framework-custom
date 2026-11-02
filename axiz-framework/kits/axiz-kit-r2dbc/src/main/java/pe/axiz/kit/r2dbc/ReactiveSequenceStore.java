package pe.axiz.kit.r2dbc;

import reactor.core.publisher.Mono;

/**
 * Native reactive counter reservation. A successful value is durably committed independently of the
 * caller's business transaction, so cached blocks cannot be allocated again after rollback.
 */
@FunctionalInterface
public interface ReactiveSequenceStore {
  Mono<Long> increment(String name, String period, int increment);
}
