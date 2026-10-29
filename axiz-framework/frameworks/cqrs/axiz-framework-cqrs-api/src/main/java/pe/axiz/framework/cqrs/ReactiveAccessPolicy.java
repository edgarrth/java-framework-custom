package pe.axiz.framework.cqrs;

import org.reactivestreams.Publisher;

/**
 * Non-blocking authorization contract, independent of Spring and Reactor. The publisher completes
 * empty to allow access or fails to deny it, before idempotency, transactions and the handler.
 *
 * @param <R> request type (or a shared supertype)
 */
public interface ReactiveAccessPolicy<R> {

  Class<R> requestType();

  Publisher<Void> check(R request, Caller caller);
}
