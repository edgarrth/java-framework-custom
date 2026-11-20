package pe.axiz.core.commons.tx;

/**
 * The business transaction completed before a subsequent side effect failed. The business operation
 * must not be retried; reconcile the side effect using its durable business identity.
 */
public interface CommittedTransactionOutcome {}
