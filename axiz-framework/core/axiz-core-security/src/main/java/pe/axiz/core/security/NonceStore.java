package pe.axiz.core.security;

import java.time.Duration;

/**
 * Recuerda los nonces usados para que un mensaje firmado se acepte una sola vez. Las
 * implementaciones deben ser atómicas entre todas las instancias del receptor (por ejemplo {@code
 * SET NX PX} de Redis); un almacén en memoria solo protege una instancia.
 */
public interface NonceStore {

  /**
   * Registra el nonce si no se vio durante el último {@code ttl}.
   *
   * @return {@code true} la primera vez, {@code false} para un nonce repetido
   * @throws RuntimeException si el almacén no puede responder; en ese caso quienes llaman deben
   *     rechazar el mensaje
   */
  boolean markUsed(String nonce, Duration ttl);
}
