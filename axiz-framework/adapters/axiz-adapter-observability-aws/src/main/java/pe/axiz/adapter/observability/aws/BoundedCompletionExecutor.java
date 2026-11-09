package pe.axiz.adapter.observability.aws;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import pe.axiz.core.commons.BoundedCallbackExecutor;

/** Owned AWS callback workers using the common bounded admission policy. */
final class BoundedCompletionExecutor extends BoundedCallbackExecutor {
  interface Observer {
    void rejected();

    void completed(long nanos);
  }

  private volatile Observer observer =
      new Observer() {
        @Override
        public void rejected() {}

        @Override
        public void completed(long nanos) {}
      };

  BoundedCompletionExecutor() {
    this(2, 256);
  }

  BoundedCompletionExecutor(int workers, int queueCapacity) {
    super(
        validWorkers(workers, queueCapacity),
        queueCapacity,
        Thread.ofPlatform().daemon().name("axiz-aws-future-", 0).factory(),
        "AWS completion");
  }

  static int validWorkers(int workers, int queueCapacity) {
    if (workers < 1 || workers > 64 || queueCapacity < 1 || queueCapacity > 65536)
      throw new IllegalArgumentException(
          "AWS completion workers must be 1..64 and queue-capacity 1..65536");
    return workers;
  }

  void observe(Observer listener) {
    observer = Objects.requireNonNull(listener);
  }

  @Override
  public void execute(Runnable command) {
    Objects.requireNonNull(command);
    long start = System.nanoTime();
    try {
      super.execute(
          () -> {
            try {
              command.run();
            } finally {
              observer.completed(System.nanoTime() - start);
            }
          });
    } catch (RejectedExecutionException failure) {
      observer.rejected();
      throw failure;
    }
  }
}
