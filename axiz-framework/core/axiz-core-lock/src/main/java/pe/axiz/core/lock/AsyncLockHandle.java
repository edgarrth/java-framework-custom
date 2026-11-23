package pe.axiz.core.lock;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Lease asíncrono con fencing por propietario. La liberación debe componerse en la ruta de
 * terminación de quien llama.
 */
public interface AsyncLockHandle {
  String resource();

  String token();

  CompletionStage<Boolean> renew(Duration lease);

  CompletionStage<Void> release();
}
