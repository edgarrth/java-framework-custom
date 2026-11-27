package pe.axiz.core.commons.tx;

/**
 * Marcador que llevan los fallos de transacción cuyo resultado de commit se desconoce. El reintento
 * automático debe detenerse incluso cuando la causa es un timeout de transporte; primero se
 * reconcilia la transacción.
 */
public interface UnknownTransactionOutcome {}
