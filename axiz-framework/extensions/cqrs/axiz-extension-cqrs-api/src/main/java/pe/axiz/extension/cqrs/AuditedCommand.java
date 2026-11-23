package pe.axiz.extension.cqrs;

import org.jspecify.annotations.Nullable;

/**
 * Comando registrado en el rastro de auditoría con su resultado.
 *
 * @param <R> tipo del resultado
 */
public interface AuditedCommand<R> extends Command<R> {

  /** Acción auditada, por ejemplo {@code card.block}. */
  String auditAction();

  /** Tipo de recurso, por ejemplo {@code card}. */
  String auditResource();

  /** Id del recurso, si se conoce antes del manejo. */
  default @Nullable String auditResourceId() {
    return null;
  }
}
