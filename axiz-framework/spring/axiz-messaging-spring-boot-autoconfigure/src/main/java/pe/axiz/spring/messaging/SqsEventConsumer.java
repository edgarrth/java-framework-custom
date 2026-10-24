package pe.axiz.spring.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import pe.axiz.core.messaging.DeliveryDecision;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.sqs.SqsEnvelopes;
import pe.axiz.core.resilience.Backoff;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Long-polling SQS consumer, one virtual thread per queue. ACK deletes the message, RETRY extends
 * its visibility with exponential backoff, DEFER (another delivery holds it in the inbox) extends
 * it until that lease may have ended, DEAD_LETTER sends it to the configured dead-letter queue (or
 * leaves it to the queue's redrive policy).
 *
 * <p>Only a message that cannot be decoded goes to the dead-letter path immediately; a failure of
 * the infrastructure around a valid message (inbox, tracing) is retried. While a batch is being
 * processed, the visibility of every message received and not yet settled is renewed, so messages
 * waiting for their turn (or a slow handler) are not redelivered to another replica. Stopping lets
 * the current batch finish (up to {@code shutdownTimeout}) before interrupting the pollers.
 */
public class SqsEventConsumer implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(SqsEventConsumer.class);
  private static final int MAX_VISIBILITY_SECONDS = 43_200;
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

  /** A received message not yet settled. */
  private record InFlight(String queue, String receiptHandle) {}

  private final SqsClient sqs;
  private final ObservedDispatcher dispatcher;
  private final AxizMessagingProperties.Sqs settings;
  private final Backoff backoff;
  private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();
  private volatile boolean running;
  private List<Thread> workers = List.of();
  private @Nullable ScheduledExecutorService heartbeat;
  private @Nullable ScheduledFuture<?> heartbeatTask;

  public SqsEventConsumer(
      SqsClient sqs,
      ObservedDispatcher dispatcher,
      AxizMessagingProperties.Sqs settings,
      Backoff backoff) {
    this.sqs = Objects.requireNonNull(sqs);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.settings = Objects.requireNonNull(settings);
    this.backoff = Objects.requireNonNull(backoff);
  }

  @Override
  public void start() {
    running = true;
    long period = Math.max(1, settings.visibilityTimeout().toSeconds() / 2);
    ScheduledExecutorService hb =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("axiz-sqs-visibility").factory());
    heartbeatTask =
        hb.scheduleWithFixedDelay(this::renewVisibilitySafely, period, period, TimeUnit.SECONDS);
    heartbeat = hb;
    workers =
        settings.queues().stream()
            .map(
                q ->
                    Thread.ofVirtual()
                        .name("axiz-sqs-" + q.substring(q.lastIndexOf('/') + 1))
                        .start(() -> poll(q)))
            .toList();
  }

  @Override
  public void stop() {
    running = false;
    long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
    for (Thread w : workers) {
      try {
        long left = deadline - System.nanoTime();
        if (left <= 0 || !w.join(Duration.ofNanos(left))) {
          w.interrupt(); // still blocked: give up on it
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        workers.forEach(Thread::interrupt);
        break;
      }
    }
    ScheduledFuture<?> task = heartbeatTask;
    if (task != null) {
      task.cancel(false);
    }
    ScheduledExecutorService hb = heartbeat;
    if (hb != null) {
      hb.shutdownNow();
    }
    if (!inFlight.isEmpty()) {
      LOG.warn("{} SQS messages left unsettled at shutdown; SQS redelivers them", inFlight.size());
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  private void poll(String queue) {
    ReceiveMessageRequest request =
        ReceiveMessageRequest.builder()
            .queueUrl(queue)
            .maxNumberOfMessages(Math.min(10, Math.max(1, settings.batchSize())))
            .waitTimeSeconds((int) Math.min(20, settings.waitTime().toSeconds()))
            .visibilityTimeout((int) settings.visibilityTimeout().toSeconds())
            .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
            .build();
    while (running && !Thread.currentThread().isInterrupted()) {
      List<Message> batch;
      try {
        batch = sqs.receiveMessage(request).messages();
      } catch (RuntimeException e) {
        if (!running) {
          return;
        }
        LOG.warn("SQS receive failed for {}: {}", queue, e.toString());
        sleep(Duration.ofSeconds(5));
        continue;
      }
      batch.forEach(m -> inFlight.put(m.messageId(), new InFlight(queue, m.receiptHandle())));
      for (Message m : batch) {
        if (!running) {
          // shutting down: leave the rest of the batch to SQS (visible again soon)
          batch.forEach(left -> inFlight.remove(left.messageId()));
          return;
        }
        try {
          process(queue, m);
        } catch (RuntimeException e) {
          LOG.warn("Could not settle SQS message {}: {}", m.messageId(), e.toString());
        } finally {
          inFlight.remove(m.messageId());
        }
      }
    }
  }

  /**
   * Heartbeat body: an exception escaping a periodic task would silently cancel every later run, so
   * nothing is let through.
   */
  private void renewVisibilitySafely() {
    try {
      renewVisibility();
    } catch (RuntimeException e) {
      LOG.warn("SQS visibility heartbeat failed; it runs again on the next period", e);
    }
  }

  /** Extends the visibility of every message received and not yet settled. */
  void renewVisibility() {
    int seconds = (int) Math.min(MAX_VISIBILITY_SECONDS, settings.visibilityTimeout().toSeconds());
    for (InFlight f : List.copyOf(inFlight.values())) {
      try {
        sqs.changeMessageVisibility(
            b -> b.queueUrl(f.queue()).receiptHandle(f.receiptHandle()).visibilityTimeout(seconds));
      } catch (RuntimeException e) {
        // the message may become visible again and be delivered twice (the inbox absorbs it)
        LOG.warn("Could not extend visibility on {}: {}", f.queue(), e.toString());
      }
    }
  }

  int inFlightCount() {
    return inFlight.size();
  }

  void process(String queue, Message m) {
    int attempt = SqsEnvelopes.attempt(m);
    EventEnvelope envelope;
    try {
      envelope = SqsEnvelopes.from(m);
    } catch (RuntimeException e) {
      // a message that cannot be read never will be: permanent
      LOG.warn("Unreadable message {} on {}: {}", m.messageId(), queue, e.toString());
      deadLetter(queue, m, null);
      return;
    }
    EventDispatcher.Outcome outcome;
    try {
      outcome = dispatcher.dispatch(queue, envelope, attempt);
    } catch (RuntimeException e) {
      // infrastructure around a valid message (tracing, context): transient
      LOG.warn("Dispatch of {} on {} failed: {}", envelope.id(), queue, e.toString());
      outcome = new EventDispatcher.Outcome(DeliveryDecision.RETRY, Duration.ZERO);
    }
    EventDispatcher.Outcome decided = outcome;
    // exhaustive: a new decision must say what happens to the message
    Runnable action =
        switch (decided.decision()) {
          case ACK -> () -> delete(queue, m);
          case RETRY -> () -> delay(queue, m, backoff.delay(attempt));
          case DEFER -> () -> delay(queue, m, decided.retryAfter().plusSeconds(1));
          case DEAD_LETTER -> () -> deadLetter(queue, m, envelope);
        };
    action.run();
  }

  private void delay(String queue, Message m, Duration delay) {
    int seconds = (int) Math.min(MAX_VISIBILITY_SECONDS, Math.max(1, delay.toSeconds()));
    sqs.changeMessageVisibility(
        b -> b.queueUrl(queue).receiptHandle(m.receiptHandle()).visibilityTimeout(seconds));
  }

  private void deadLetter(String queue, Message m, @Nullable EventEnvelope envelope) {
    String dlq = settings.deadLetterQueues().get(queue);
    if (dlq == null) {
      // the queue's redrive policy moves it after maxReceiveCount; make it visible again now
      sqs.changeMessageVisibility(
          b -> b.queueUrl(queue).receiptHandle(m.receiptHandle()).visibilityTimeout(0));
      return;
    }
    sqs.sendMessage(
        b ->
            b.queueUrl(dlq)
                .messageBody(m.body())
                .messageAttributes(Map.copyOf(m.messageAttributes())));
    delete(queue, m);
    LOG.warn(
        "Event {} moved to dead-letter queue", envelope != null ? envelope.id() : m.messageId());
  }

  private void delete(String queue, Message m) {
    sqs.deleteMessage(b -> b.queueUrl(queue).receiptHandle(m.receiptHandle()));
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
