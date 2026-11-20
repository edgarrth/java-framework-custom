package pe.axiz.adapter.architecturefixture;

import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

public class DirectScheduledClient {
  public Disposable call(Scheduler scheduler) {
    return scheduler.schedule(() -> {});
  }
}
