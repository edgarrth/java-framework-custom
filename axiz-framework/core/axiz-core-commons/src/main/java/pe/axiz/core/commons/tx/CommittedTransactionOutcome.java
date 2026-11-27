package pe.axiz.core.commons.tx;

/**
 * La transacción de negocio terminó antes de que fallara un efecto secundario posterior. La
 * operación de negocio no debe reintentarse; el efecto secundario se reconcilia mediante su
 * identidad de negocio durable.
 */
public interface CommittedTransactionOutcome {}
