package pe.axiz.core.lock;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Asynchronous owner-fenced lease. Release must be composed into the caller's termination path. */
public interface AsyncLockHandle {
  String resource();

  String token();

  CompletionStage<Boolean> renew(Duration lease);

  CompletionStage<Void> release();
}
