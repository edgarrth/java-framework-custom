package pe.axiz.spring.audit;

/**
 * Define qué hace la auditoría durable con una acción exitosa registrada fuera de una transacción.
 */
public enum AuditOutsideTransaction {
  /** Se publica mediante el publicador asíncrono (no durable). */
  ASYNC,
  /** Se rechaza: todo éxito auditado debe formar parte de una transacción de negocio. */
  FAIL
}
