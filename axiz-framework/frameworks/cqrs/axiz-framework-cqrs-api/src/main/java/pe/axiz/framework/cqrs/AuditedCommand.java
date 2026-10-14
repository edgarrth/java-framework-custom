package pe.axiz.framework.cqrs;

import org.jspecify.annotations.Nullable;

/**
 * Command recorded in the audit trail with its outcome.
 *
 * @param <R> result type
 */
public interface AuditedCommand<R> extends Command<R> {

  /** Audited action, e.g. {@code card.block}. */
  String auditAction();

  /** Resource type, e.g. {@code card}. */
  String auditResource();

  /** Resource id, if known before handling. */
  default @Nullable String auditResourceId() {
    return null;
  }
}
