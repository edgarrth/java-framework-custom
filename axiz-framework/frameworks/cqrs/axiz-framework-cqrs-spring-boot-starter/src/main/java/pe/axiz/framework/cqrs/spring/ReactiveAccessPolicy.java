package pe.axiz.framework.cqrs.spring;

import pe.axiz.framework.cqrs.AccessPolicy;
import pe.axiz.framework.cqrs.Caller;
import reactor.core.publisher.Mono;

/**
 * Non-blocking {@link AccessPolicy} for the reactive buses: for policies that read state through a
 * reactive repository (the owner of a resource) without blocking. The returned {@code Mono}
 * completes empty to allow and errors with {@code AccessException} or {@code NotFoundException} to
 * deny. It runs in the authorization stage, after the blocking policies and before idempotency,
 * transactions and handlers; it also counts as coverage for {@code
 * axiz.cqrs.authorization.unprotected}.
 *
 * @param <R> request type (or a supertype shared by several requests)
 */
public interface ReactiveAccessPolicy<R> {

  Class<R> requestType();

  Mono<Void> check(R request, Caller caller);
}
