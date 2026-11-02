package pe.axiz.extension.cqrs.spring;

import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.Caller;
import reactor.core.publisher.Mono;

/**
 * Non-blocking {@link AccessPolicy} for the reactive buses: for policies that read state through a
 * reactive repository (the owner of a resource) without blocking. The returned {@code Mono}
 * completes empty to allow and errors with {@code AccessException} or {@code NotFoundException} to
 * deny. It runs in the native authorization stage, before idempotency, transactions and handlers;
 * it also counts as coverage for {@code axiz.cqrs.authorization.unprotected}.
 *
 * @param <R> request type (or a supertype shared by several requests)
 * @deprecated implement {@link pe.axiz.extension.cqrs.ReactiveAccessPolicy} from the API module.
 */
@Deprecated(forRemoval = true)
public interface ReactiveAccessPolicy<R> extends pe.axiz.extension.cqrs.ReactiveAccessPolicy<R> {

  @Override
  Mono<Void> check(R request, Caller caller);
}
