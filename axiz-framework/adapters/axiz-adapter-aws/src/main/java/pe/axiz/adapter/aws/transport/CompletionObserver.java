package pe.axiz.adapter.aws.transport;

import software.amazon.awssdk.utils.SdkAutoCloseable;

/** Non-blocking completion signals; the resource owner releases the observer on shutdown. */
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
