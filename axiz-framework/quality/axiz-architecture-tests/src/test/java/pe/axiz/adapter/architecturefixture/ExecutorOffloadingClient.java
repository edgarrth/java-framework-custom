package pe.axiz.adapter.architecturefixture;

import java.util.concurrent.Executor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class ExecutorOffloadingClient {
  public Scheduler call(Executor executor) {
    return Schedulers.fromExecutor(executor);
  }
}
