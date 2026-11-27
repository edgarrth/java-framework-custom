package pe.axiz.core.lock;

/**
 * Lease que se mantiene mientras un {@link LockTemplate} ejecuta un trabajo.
 *
 * <p>Un lease puede perderse mientras el trabajo corre: el almacén no lo renovó a tiempo (una
 * pausa, una partición de red) y otro propietario puede tener ya el recurso. La plantilla lo
 * informa como una {@link LockLostException} cuando el trabajo termina, pero una excepción al final
 * no deshace lo que el trabajo ya hizo. El trabajo con efectos externos (un débito, una
 * transferencia, un mensaje) debería llamar a {@link #ensureHeld()} justo antes de cada efecto, y
 * el propio recurso debería rechazar las escrituras de un propietario anterior: una escritura
 * condicional con {@link #token()}, una transacción de base de datos o una clave de idempotencia
 * persistida.
 */
public interface LockLease {

  String resource();

  /**
   * Token de este propietario, único por adquisición; se usa en escrituras condicionales (fencing).
   */
  String token();

  /** Indica si se sabe que el lease sigue retenido. */
  boolean held();

  /**
   * Falla rápido cuando el lease se perdió.
   *
   * @throws LockLostException cuando otro propietario puede tener el recurso
   */
  void ensureHeld();
}
