package pe.axiz.core.audit;

/**
 * Confirma la aceptación durable antes de retornar. El fallo debe propagarse; una cola en memoria o
 * una escritura en log no pueden cumplir este contrato. Las implementaciones sellan el hecho
 * inmutable final y usan un plazo de confirmación finito. Un fallo o una interrupción no prueba que
 * no se haya almacenado nada.
 */
@FunctionalInterface
public interface StrictAuditPublisher {
  void publish(AuditEvent event);
}
