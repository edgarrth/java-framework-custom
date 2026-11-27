package pe.axiz.core.lock;

import java.time.Duration;

/** Lock adquirido. Se cierra (try-with-resources) para liberarlo. */
public interface LockHandle extends AutoCloseable {

  String resource();

  /** Token único de este propietario; solo el propietario puede renovar o liberar. */
  String token();

  /** Extiende un lease vivo; devuelve false cuando expiró o pertenece a otro propietario. */
  boolean renew(Duration lease);

  void release();

  @Override
  default void close() {
    release();
  }
}
