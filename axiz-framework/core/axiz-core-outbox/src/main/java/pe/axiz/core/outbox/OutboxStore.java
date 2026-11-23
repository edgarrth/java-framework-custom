package pe.axiz.core.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Puerto de persistencia del outbox. {@link #append} corre dentro de la transacción de negocio. */
public interface OutboxStore {

  /**
   * Identidad del recurso usada para asegurar que las cabezas de auditoría y los registros
   * compartan una transacción.
   */
  default @Nullable Object transactionResource() {
    return null;
  }

  void append(OutboxRecord record);

  /**
   * Reclama de forma atómica las entradas vencidas usando la hora de la base de datos para la
   * elegibilidad y la expiración del lease. El lease no empieza antes de esta llamada, nunca a
   * partir de un reloj de la aplicación ni de la marca de tiempo de una transacción circundante
   * anterior. Las implementaciones devuelven el propietario asignado y la expiración informativa
   * del lease; las actualizaciones posteriores deben aplicar fencing sobre ese propietario.
   */
  List<OutboxRecord> claimDue(int size, Duration lease, String owner);

  boolean markPublished(String id, String owner, Instant at);

  boolean markFailed(
      String id,
      String owner,
      int attempts,
      Instant nextAttemptAt,
      @Nullable String error,
      boolean giveUp);

  boolean retry(String id);

  boolean skip(String id, String reason);

  long countBlockedAggregates();

  long countSkipped();

  void verifySchema();

  /** Borra las entradas publicadas anteriores al instante; devuelve cuántas se eliminaron. */
  int purgePublished(Instant before);

  long countPending();
}
