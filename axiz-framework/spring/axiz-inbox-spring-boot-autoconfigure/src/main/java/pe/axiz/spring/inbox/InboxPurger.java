package pe.axiz.spring.inbox;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import pe.axiz.core.inbox.InboxStore;

/** Deletes expired inbox rows periodically on a virtual thread (JDBC and R2DBC stores). */
public class InboxPurger implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(InboxPurger.class);

  private final InboxStore store;
  private final Duration interval;
  private final Clock clock;
  private volatile boolean running;
  private Thread worker = Thread.ofVirtual().unstarted(() -> {});

  public InboxPurger(InboxStore store, Duration interval, Clock clock) {
    this.store = store;
    this.interval = interval;
    this.clock = clock;
  }

  @Override
  public void start() {
    running = true;
    worker =
        Thread.ofVirtual()
            .name("axiz-inbox-purge")
            .start(
                () -> {
                  while (running) {
                    try {
                      Thread.sleep(interval);
                      int n = store.purgeExpired(clock.instant());
                      LOG.debug("Purged {} expired inbox rows", n);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    } catch (RuntimeException e) {
                      LOG.warn("Inbox purge failed: {}", e.toString());
                    }
                  }
                });
  }

  @Override
  public void stop() {
    running = false;
    worker.interrupt();
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
