package pe.axiz.adapter.aws.transport;

import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Señales de finalización no bloqueantes; el propietario del recurso libera el observador al
 * apagar.
 */
public interface CompletionObserver extends SdkAutoCloseable {
  CompletionObserver NOOP =
      new CompletionObserver() {
        @Override
        public void rejected() {}

        @Override
        public void completed(long nanos) {}
      };

  void rejected();

  void completed(long nanos);

  @Override
  default void close() {}
}
