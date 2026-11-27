package pe.axiz.adapter.architecturefixture;

import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

public class WorkerScheduledClient {
  public Disposable call(Scheduler scheduler) {
    return scheduler.createWorker().schedule(() -> {});
  }
}
