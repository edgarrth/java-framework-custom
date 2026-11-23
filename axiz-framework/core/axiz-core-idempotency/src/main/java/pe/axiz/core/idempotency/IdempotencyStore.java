package pe.axiz.core.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Almacenamiento de registros de idempotencia (Redis o DynamoDB en producción).
 *
 * <p>Cada escritura posterior a {@link #tryStart} tiene fencing por el token de propietario del
 * registro: una ejecución cuya retención expiró y cuya clave se volvió a adquirir no puede
 * completar ni liberar la clave del nuevo propietario.
 */
public interface IdempotencyStore {

  /**
   * Comparación atómica del registro completo; el propietario y la versión bloquean a los
   * escritores desfasados, incluida la reutilización de la clave.
   */
  boolean compareAndSet(IdempotencyRecord expected, @Nullable IdempotencyRecord replacement);

  default boolean renew(
      String key, String owner, Instant leaseUntil, Instant retainUntil, Instant now) {
    for (int attempt = 0; attempt < 16; attempt++) {
      Optional<IdempotencyRecord> found = find(key, now);
      if (found.isEmpty()) return false;
      IdempotencyRecord current = found.get();
      if (!current.heldBy(owner) || !current.leaseUntil().isAfter(now)) return false;
      if (compareAndSet(current, current.renewed(leaseUntil, retainUntil))) return true;
    }
    return false;
  }

  default boolean markUnknown(String key, String owner, String diagnostic, Instant now) {
    for (int attempt = 0; attempt < 16; attempt++) {
      Optional<IdempotencyRecord> found = find(key, now);
      if (found.isEmpty() || !found.get().heldBy(owner)) return false;
      if (compareAndSet(found.get(), found.get().unknown(diagnostic))) return true;
    }
    return false;
  }

  default boolean releaseWithoutEffect(String key, String owner) {
    return release(key, owner);
  }

  /**
   * La resolución aplica solo a la generación y la versión que la aplicación inspeccionó realmente.
   */
  default boolean resolve(
      IdempotencyRecord expected, Resolution resolution, Instant now, Instant retainUntil) {
    if (!expected.liveAt(now)
        || expected.status() == IdempotencyRecord.Status.COMPLETED
        || (expected.status() == IdempotencyRecord.Status.IN_PROGRESS
            && expected.leaseUntil().isAfter(now))) {
      return false;
    }
    StoredResponse response = resolution.response();
    return compareAndSet(
        expected, response == null ? null : expected.completed(response, retainUntil));
  }

  /**
   * Crea de forma atómica el registro IN_PROGRESS cuando la clave está libre (ausente o pasada su
   * retención). La sola expiración del lease nunca libera la clave.
   *
   * @param record registro con su token de propietario
   * @return {@code false} cuando otro registro vivo tiene la clave
   */
  boolean tryStart(IdempotencyRecord record);

  Optional<IdempotencyRecord> find(String key, Instant now);

  /**
   * Almacena la respuesta, solo si la clave sigue IN_PROGRESS y en poder de {@code owner}.
   *
   * @return {@code false} cuando el propietario perdió la clave (la retención expiró y otra
   *     petición adquirió la clave)
   */
  boolean complete(String key, String owner, StoredResponse response, Instant expiresAt);

  /**
   * Borra el registro IN_PROGRESS tras un fallo para que el cliente pueda reintentar, solo si está
   * en poder de {@code owner}. Los registros completados se conservan.
   *
   * @return {@code false} cuando no había nada de este propietario que liberar
   */
  boolean release(String key, String owner);
}
