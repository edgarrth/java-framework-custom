package pe.axiz.core.messaging;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Puerto que usa el despachador para procesar cada mensaje una vez por consumidor (lo implementa el
 * inbox). Un claim distingue tres situaciones distintas, porque solo una de ellas permite confirmar
 * el mensaje sin ejecutar el handler:
 *
 * <ul>
 *   <li>{@link Claim.Status#ACQUIRED}: esta entrega es dueña del procesamiento hasta que termina su
 *       lease; debe finalizar con {@link #completed} o {@link #failed} pasando el token del claim.
 *   <li>{@link Claim.Status#ALREADY_PROCESSED}: se confirma, los efectos ya ocurrieron.
 *   <li>{@link Claim.Status#BUSY}: otra entrega tiene un lease válido y aún puede fallar (o puede
 *       haber muerto): el mensaje debe reentregarse más tarde, nunca confirmarse.
 * </ul>
 */
public interface Deduplicator {

  /**
   * Resultado de reclamar un mensaje.
   *
   * @param status lo que debe hacer el despachador
   * @param token token de propietario de un claim adquirido (vacío en otro caso); las
   *     actualizaciones posteriores del registro solo tienen éxito con él, de modo que una entrega
   *     cuyo lease fue tomado por otra no puede cambiar el registro del nuevo propietario
   * @param retryAfter para {@link Status#BUSY}, cuánto falta para que termine el lease actual
   */
  record Claim(Status status, String token, Duration retryAfter) {

    /** Resultado del claim. */
    public enum Status {
      ACQUIRED,
      ALREADY_PROCESSED,
      BUSY
    }

    public Claim {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(token, "token");
      Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public static Claim acquired(String token) {
      if (token.isBlank()) {
        throw new IllegalArgumentException("token required");
      }
      return new Claim(Status.ACQUIRED, token, Duration.ZERO);
    }

    public static Claim alreadyProcessed() {
      return new Claim(Status.ALREADY_PROCESSED, "", Duration.ZERO);
    }

    public static Claim busy(@Nullable Duration retryAfter) {
      return new Claim(
          Status.BUSY,
          "",
          retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter);
    }

    /** Token de propietario aleatorio. */
    public static String newToken() {
      return UUID.randomUUID().toString();
    }
  }

  /** Reclama el mensaje para esta entrega. */
  Claim claim(String consumer, String messageKey);

  /**
   * Marca el mensaje como procesado.
   *
   * @return false cuando el claim no pertenece a este token (otro tomó su lease)
   */
  boolean completed(String consumer, String messageKey, String token);

  /**
   * Libera el claim tras un fallo para que una reentrega pueda procesarlo.
   *
   * @return false cuando el claim no pertenece a este token
   */
  boolean failed(String consumer, String messageKey, String token);

  static Deduplicator none() {
    return new Deduplicator() {
      @Override
      public Claim claim(String consumer, String messageKey) {
        return Claim.acquired(Claim.newToken());
      }

      @Override
      public boolean completed(String consumer, String messageKey, String token) {
        return true;
      }

      @Override
      public boolean failed(String consumer, String messageKey, String token) {
        return true;
      }
    };
  }
}
