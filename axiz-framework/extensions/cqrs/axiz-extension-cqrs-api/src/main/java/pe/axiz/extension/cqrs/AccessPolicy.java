package pe.axiz.extension.cqrs;

/**
 * Object-level authorization of a command or query type: decides whether the caller may act on the
 * resources the request names (customer, account, order), beyond the authorities of {@link
 * RequiresAuthority}. Policies are beans; the pipeline runs every policy whose {@link
 * #requestType()} matches the request, in the authorization stage, before idempotency replays,
 * transactions and handlers.
 *
 * <p>Deny with {@code AccessException} (403), or with {@code NotFoundException} (404) when the
 * existence of another caller's resource must not be revealed. Policies may read state (for example
 * the owner of an order); reactive buses call them off the event loop.
 *
 * @param <R> request type (or a supertype shared by several requests)
 */
public interface AccessPolicy<R> {

  Class<R> requestType();

  void check(R request, Caller caller);
}
