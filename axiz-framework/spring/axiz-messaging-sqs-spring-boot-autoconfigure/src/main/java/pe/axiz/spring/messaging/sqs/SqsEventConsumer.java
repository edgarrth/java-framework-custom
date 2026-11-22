package pe.axiz.spring.messaging.sqs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.HashMap;
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
import pe.axiz.adapter.messaging.sqs.SqsEnvelopes;
import pe.axiz.core.errors.Failures;
import pe.axiz.core.messaging.DeliveryDecision;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.spring.foundation.lifecycle.WorkerLifecycle;
import pe.axiz.spring.foundation.lifecycle.WorkerStatus;
import pe.axiz.spring.messaging.ObservedDispatcher;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
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
 * the currently executing handler finish (up to 15 seconds) before interrupting the pollers.
 */
public final class SqsEventConsumer implements SmartLifecycle, WorkerStatus {

  private static final Logger LOG = LoggerFactory.getLogger(SqsEventConsumer.class);

  /** SQS accepts at most ten message attributes; the failure is added when there is room. */
  private static final int MAX_MESSAGE_ATTRIBUTES = 10;

  /** Attribute that records why the message was dead-lettered ({@code Failures.describe}). */
  static final String FAILURE_ATTRIBUTE = "axiz-failure";

  private static final int MAX_VISIBILITY_SECONDS = 43_200;
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);

  /** A received message not yet settled. */
  private static final class InFlight {
    final String queue;
    final String receiptHandle;
    boolean settled;

    InFlight(String queue, String receiptHandle) {
      this.queue = queue;
      this.receiptHandle = receiptHandle;
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "SqsClient is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final SqsClient sqs;

  private final ObservedDispatcher dispatcher;
  private final AxizMessagingSqsProperties settings;
  private final Backoff backoff;
  private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();
  private final WorkerLifecycle lifecycle;
  private @Nullable ScheduledExecutorService heartbeat;
  private @Nullable ScheduledFuture<?> heartbeatTask;

  public SqsEventConsumer(
      SqsClient sqs,
      ObservedDispatcher dispatcher,
      AxizMessagingSqsProperties settings,
      Backoff backoff) {
    this.sqs = Objects.requireNonNull(sqs);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.settings = Objects.requireNonNull(settings);
    this.backoff = Objects.requireNonNull(backoff);
    this.lifecycle =
        new WorkerLifecycle("axiz-sqs", SHUTDOWN_TIMEOUT, this::pollers, this::closeHeartbeat);
  }

  private List<Runnable> pollers() {
    for (String queue : settings.queues()) {
      if (!settings.deadLetterQueues().containsKey(queue)) {
        String policy =
            sqs.getQueueAttributes(
                    b -> b.queueUrl(queue).attributeNames(QueueAttributeName.REDRIVE_POLICY))
                .attributes()
                .get(QueueAttributeName.REDRIVE_POLICY);
        if (policy == null || policy.isBlank())
          throw new IllegalStateException(
              "SQS consumers require an explicit DLQ or native redrive policy");
      }
    }
    long period = Math.max(1, settings.visibilityTimeout().toSeconds() / 2);
    ScheduledExecutorService hb =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("axiz-sqs-visibility").factory());
    heartbeatTask =
        hb.scheduleWithFixedDelay(this::renewVisibilitySafely, period, period, TimeUnit.SECONDS);
    heartbeat = hb;
    return settings.queues().stream().map(q -> (Runnable) () -> poll(q)).toList();
  }

  private void closeHeartbeat() {
    ScheduledFuture<?> task = heartbeatTask;
    if (task != null) task.cancel(false);
    ScheduledExecutorService hb = heartbeat;
    if (hb != null) {
      hb.shutdownNow();
      boolean interrupted = false;
      try {
        while (!hb.isTerminated()) {
          try {
            hb.awaitTermination(1, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            interrupted = true;
          }
        }
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
    heartbeatTask = null;
    heartbeat = null;
  }

  @Override
  public WorkerStatus.Snapshot workerSnapshot() {
    return lifecycle.workerSnapshot();
  }

  @Override
  public Duration shutdownGrace() {
    return lifecycle.shutdownGrace();
  }

  @Override
  public void start() {
    lifecycle.start();
  }

  @Override
  public void stop() {
    lifecycle.stop();
  }

  @Override
  public void stop(Runnable callback) {
    lifecycle.stop(callback);
  }

  @Override
  public boolean isRunning() {
    return lifecycle.isRunning();
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  private void poll(String queue) {
    ReceiveMessageRequest request =
        ReceiveMessageRequest.builder()
            .queueUrl(queue)
            // A FIFO head must be settled before another message from its group is delivered.
            // One receipt avoids acknowledging later group members while the head backs off.
            .maxNumberOfMessages(queue.endsWith(".fifo") ? 1 : Math.min(10, settings.batchSize()))
            .waitTimeSeconds((int) Math.min(20, settings.waitTime().toSeconds()))
            // Receive long-polling has a different bound from short publisher API calls.
            .overrideConfiguration(c -> c.apiCallTimeout(settings.waitTime().plusSeconds(10)))
            .visibilityTimeout((int) settings.visibilityTimeout().toSeconds())
            .messageSystemAttributeNames(
                MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                MessageSystemAttributeName.MESSAGE_GROUP_ID)
            .build();
    while (lifecycle.accepting() && !Thread.currentThread().isInterrupted()) {
      List<Message> batch;
      try {
        batch = sqs.receiveMessage(request).messages();
      } catch (RuntimeException e) {
        if (!lifecycle.accepting()) {
          return;
        }
        LOG.warn("SQS receive failed for {}: {}", queue, e.getClass().getSimpleName());
        sleep(Duration.ofSeconds(5));
        continue;
      }
      batch.forEach(m -> inFlight.put(m.messageId(), new InFlight(queue, m.receiptHandle())));
      for (Message m : batch) {
        if (!lifecycle.accepting()) {
          // shutting down: leave the rest of the batch to SQS (visible again soon)
          batch.forEach(left -> inFlight.remove(left.messageId()));
          return;
        }
        try {
          process(queue, m);
        } catch (RuntimeException e) {
          LOG.warn(
              "Could not settle SQS message {}: {}", m.messageId(), e.getClass().getSimpleName());
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
      LOG.warn(
          "SQS visibility heartbeat failed; it runs again on the next period; failureType={}",
          e.getClass().getSimpleName());
    }
  }

  /** Extends the visibility of every message received and not yet settled. */
  void renewVisibility() {
    int seconds = (int) Math.min(MAX_VISIBILITY_SECONDS, settings.visibilityTimeout().toSeconds());
    for (InFlight f : List.copyOf(inFlight.values())) {
      synchronized (f) {
        if (f.settled) continue;
        try {
          sqs.changeMessageVisibility(
              b -> b.queueUrl(f.queue).receiptHandle(f.receiptHandle).visibilityTimeout(seconds));
        } catch (RuntimeException e) {
          // The inbox absorbs duplicates when visibility cannot be extended.
          LOG.warn("Could not extend visibility on {}: {}", f.queue, e.getClass().getSimpleName());
        }
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
      String failure = "unreadable message: " + Failures.describe(e);
      LOG.warn("Unreadable message {} on {}: {}", m.messageId(), queue, failure);
      settle(m, () -> deadLetter(queue, m, null, failure));
      return;
    }
    EventDispatcher.Outcome outcome;
    try {
      outcome = dispatcher.dispatch(queue, envelope, attempt);
    } catch (RuntimeException e) {
      // infrastructure around a valid message (tracing, context): transient
      LOG.warn(
          "Dispatch of {} on {} failed: {}", envelope.id(), queue, e.getClass().getSimpleName());
      outcome = new EventDispatcher.Outcome(DeliveryDecision.RETRY, Duration.ZERO);
    }
    EventDispatcher.Outcome decided = outcome;
    // exhaustive: a new decision must say what happens to the message
    Runnable action =
        switch (decided.decision()) {
          case ACK -> () -> delete(queue, m);
          case RETRY -> () -> delay(queue, m, backoff.delay(attempt));
          case DEFER -> () -> delay(queue, m, decided.retryAfter().plusSeconds(1));
          case DEAD_LETTER -> () -> deadLetter(queue, m, envelope, decided.failure());
        };
    settle(m, action);
  }

  private void settle(Message message, Runnable action) {
    InFlight flight = inFlight.remove(message.messageId());
    if (flight == null) {
      action.run();
      return;
    }
    synchronized (flight) {
      // A heartbeat that already copied this receipt must finish before the final visibility.
      flight.settled = true;
      action.run();
    }
  }

  private void delay(String queue, Message m, Duration delay) {
    int seconds = (int) Math.min(MAX_VISIBILITY_SECONDS, Math.max(1, delay.toSeconds()));
    sqs.changeMessageVisibility(
        b -> b.queueUrl(queue).receiptHandle(m.receiptHandle()).visibilityTimeout(seconds));
  }

  private void deadLetter(
      String queue, Message m, @Nullable EventEnvelope envelope, @Nullable String failure) {
    String dlq = settings.deadLetterQueues().get(queue);
    if (dlq == null) {
      // the queue's redrive policy moves it after maxReceiveCount; make it visible again now
      sqs.changeMessageVisibility(
          b -> b.queueUrl(queue).receiptHandle(m.receiptHandle()).visibilityTimeout(0));
      return;
    }
    sqs.sendMessage(
        b -> {
          Map<String, MessageAttributeValue> attributes = new HashMap<>(m.messageAttributes());
          if (failure != null && attributes.size() < MAX_MESSAGE_ATTRIBUTES)
            attributes.put(
                FAILURE_ATTRIBUTE,
                MessageAttributeValue.builder().dataType("String").stringValue(failure).build());
          b.queueUrl(dlq).messageBody(m.body()).messageAttributes(Map.copyOf(attributes));
          if (dlq.endsWith(".fifo")) {
            b.messageGroupId(
                    m.attributes()
                        .getOrDefault(
                            MessageSystemAttributeName.MESSAGE_GROUP_ID, "axiz-dead-letter"))
                .messageDeduplicationId(m.messageId());
          }
        });
    delete(queue, m);
    LOG.warn(
        "Event {} moved to dead-letter queue; failure={}",
        envelope != null ? envelope.id() : m.messageId(),
        failure != null ? failure : "unknown");
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
