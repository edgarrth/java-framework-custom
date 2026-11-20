package pe.axiz.core.commons.tx;

/**
 * Marker carried by transaction failures whose commit outcome is unknown. Automatic retry must stop
 * even when the cause is a transport timeout; reconcile the transaction first.
 */
public interface UnknownTransactionOutcome {}
