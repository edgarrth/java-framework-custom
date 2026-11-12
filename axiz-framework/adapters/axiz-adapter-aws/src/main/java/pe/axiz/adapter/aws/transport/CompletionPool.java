package pe.axiz.adapter.aws.transport;

/** Read-only completion admission statistics, without executor lifecycle access. */
public interface CompletionPool {
  int getActiveCount();

  int getQueueSize();
}
