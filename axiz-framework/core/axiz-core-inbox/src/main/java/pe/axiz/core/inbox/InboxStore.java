package pe.axiz.core.inbox;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Registro de mensajes consumidos por consumidor (patrón de consumidor idempotente). Cada registro
 * en curso tiene un token de propietario: completarlo o liberarlo solo funciona para la entrega que
 * lo tiene, de modo que una entrega cuyo lease expiró y fue tomado por otra no puede cambiar el
 * registro del nuevo propietario.
 */
public interface InboxStore {

  /**
   * Resultado de un intento de adquisición.
   *
   * @param status lo que ocurrió
   * @param leaseUntil fin del lease actual cuando {@link Status#BUSY}
   */
  record Acquisition(Status status, @Nullable Instant leaseUntil) {

    /** Resultado de la adquisición. */
    public enum Status {
      /** Este llamador es dueño del procesamiento. */
      ACQUIRED,
      /** Ya procesado por una entrega previa. */
      PROCESSED,
      /** Otra entrega tiene un lease válido. */
      BUSY
    }

    public Acquisition {
      Objects.requireNonNull(status, "status");
    }

    public static Acquisition acquired() {
      return new Acquisition(Status.ACQUIRED, null);
    }

    public static Acquisition processed() {
      return new Acquisition(Status.PROCESSED, null);
    }

    public static Acquisition busy(@Nullable Instant leaseUntil) {
      return new Acquisition(Status.BUSY, leaseUntil);
    }
  }

  /**
   * Registra el mensaje como en curso para {@code owner} hasta {@code leaseUntil}, o toma un
   * registro cuyo lease terminó (su entrega murió).
   */
  Acquisition tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt);

  /**
   * Marca el mensaje como procesado solo mientras el lease de su propietario y la retención sigan
   * vivos.
   *
   * @return false cuando {@code owner} no tiene un registro vivo en {@code at}
   */
  boolean markProcessed(String consumer, String messageId, String owner, Instant at);

  /**
   * Elimina el registro en curso de {@code owner} tras un fallo para que una reentrega pueda
   * procesarlo.
   *
   * @return false cuando {@code owner} no tiene el registro
   */
  boolean release(String consumer, String messageId, String owner);

  /**
   * Valida la ventana de persistencia antes de cualquier E/S, incluidos los consumidores directos
   * del almacén.
   */
  static void validateWindow(Instant now, Instant leaseUntil, Instant expiresAt) {
    if (!leaseUntil.isAfter(now) || !expiresAt.isAfter(leaseUntil))
      throw new IllegalArgumentException(
          "Inbox retention must outlive a positive processing lease");
  }

  /** Borra las entradas completadas pasada su retención; nunca borra un registro en curso. */
  int purgeExpired(Instant now);

  /**
   * Verifica al arrancar que el almacenamiento tenga la disposición requerida (por ejemplo la
   * columna {@code owner}), de modo que una actualización que omitió una migración falle antes de
   * consumir mensajes.
   *
   * @throws InboxException con la actualización a aplicar
   */
  default void verifySchema() {}
}
