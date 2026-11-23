package pe.axiz.core.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * Puerto de idempotencia asíncrono nativo; cada mutación tiene fencing atómico por su token de
 * propietario.
 */
public interface AsyncIdempotencyStore {
  CompletionStage<Boolean> compareAndSet(
      IdempotencyRecord expected, @Nullable IdempotencyRecord replacement);

  /**
   * Valor por defecto solo para almacenes locales inmediatos. Los proveedores asíncronos remotos
   * deben sobrescribir este método y delegar en AsyncIdempotencyCas con su reloj inyectado, para
   * que la contención nunca congele las verificaciones del lease en el instante inicial de quien
   * llama.
   */
  default CompletionStage<Boolean> renew(
      String key, String owner, Instant leaseUntil, Instant retainUntil, Instant now) {
    return AsyncIdempotencyCas.mutate(
        this,
        key,
        owner,
        () -> now,
        current ->
            current.leaseUntil().isAfter(now) ? current.renewed(leaseUntil, retainUntil) : null,
        0);
  }

  /**
   * Valor por defecto solo para almacenes locales inmediatos. Los proveedores asíncronos remotos
   * deben sobrescribir este método y delegar en AsyncIdempotencyCas con su reloj inyectado en cada
   * intento.
   */
  default CompletionStage<Boolean> markUnknown(
      String key, String owner, String diagnostic, Instant now) {
    return AsyncIdempotencyCas.mutate(
        this, key, owner, () -> now, current -> current.unknown(diagnostic), 0);
  }

  default CompletionStage<Boolean> releaseWithoutEffect(String key, String owner) {
    return release(key, owner);
  }

  default CompletionStage<Boolean> resolve(
      IdempotencyRecord expected, Resolution resolution, Instant now, Instant retainUntil) {
    if (!expected.liveAt(now)
        || expected.status() == IdempotencyRecord.Status.COMPLETED
        || (expected.status() == IdempotencyRecord.Status.IN_PROGRESS
            && expected.leaseUntil().isAfter(now))) {
      return CompletableFuture.completedFuture(false);
    }
    StoredResponse response = resolution.response();
    return compareAndSet(
        expected, response == null ? null : expected.completed(response, retainUntil));
  }

  CompletionStage<Boolean> tryStart(IdempotencyRecord record);

  CompletionStage<Optional<IdempotencyRecord>> find(String key, Instant now);

  CompletionStage<Boolean> complete(
      String key, String owner, StoredResponse response, Instant expiresAt);

  CompletionStage<Boolean> release(String key, String owner);
}
