package pe.axiz.core.audit;

import java.util.concurrent.CompletionStage;

/**
 * Confirmación nativa de la aceptación durable, incluido el commit cuando se usa un outbox. El
 * envío nunca espera E/S; los fallos se propagan y nunca se convierten en un fallback exitoso de
 * cola o log. La cancelación puede impedir trabajo adicional pero no puede retractar una escritura
 * durable completada.
 */
@FunctionalInterface
public interface AsyncStrictAuditPublisher {
  CompletionStage<Void> publish(AuditEvent event);
}
