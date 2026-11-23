package pe.axiz.core.audit;

import java.util.concurrent.CompletionStage;

/**
 * Entrega asíncrona nativa con la política de aceptación documentada por la implementación. Un
 * publicador con cola confirma la admisión en memoria; un transporte puede confirmar el acuse de su
 * broker. Se usa {@link AsyncStrictAuditPublisher} cuando se requiere aceptación durable. El envío
 * nunca espera E/S. La cancelación debería llegar al transporte donde esté soportada; no puede
 * retractar un registro ya aceptado por un broker.
 */
@FunctionalInterface
public interface AsyncAuditEventPublisher {
  CompletionStage<Void> publish(AuditEvent event);
}
