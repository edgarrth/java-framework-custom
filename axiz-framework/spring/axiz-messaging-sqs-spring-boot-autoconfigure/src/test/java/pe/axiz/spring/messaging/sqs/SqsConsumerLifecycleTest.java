package pe.axiz.spring.messaging.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.ConsumerRetryPolicy;
import pe.axiz.core.messaging.Deduplicator;
import pe.axiz.core.messaging.DeliveryDecision;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.spring.messaging.ObservedDispatcher;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsConsumerLifecycleTest {
  @Test
  void startupRequiresAnExplicitDlqOrNativeRedrivePolicy() {
    var sqs = mock(SqsClient.class, CALLS_REAL_METHODS);
    doReturn(GetQueueAttributesResponse.builder().attributes(Map.of()).build())
        .when(sqs)
        .getQueueAttributes(any(GetQueueAttributesRequest.class));
    var consumer =
        new SqsEventConsumer(
            sqs,
            mock(ObservedDispatcher.class),
            new AxizMessagingSqsProperties(
                List.of("queue"), Map.of(), Duration.ofSeconds(1), 10, Duration.ofSeconds(60)),
            Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    assertThrows(IllegalStateException.class, consumer::start);
    Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !consumer.isRunning());
    doReturn(
            GetQueueAttributesResponse.builder()
                .attributes(
                    Map.of(
                        QueueAttributeName.REDRIVE_POLICY,
                        "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:dlq\",\"maxReceiveCount\":3}"))
                .build())
        .when(sqs)
        .getQueueAttributes(any(GetQueueAttributesRequest.class));
    doAnswer(
            call -> {
              new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
              return ReceiveMessageResponse.builder().build();
            })
        .when(sqs)
        .receiveMessage(any(ReceiveMessageRequest.class));
    consumer.start();
    try {
      assertTrue(consumer.isRunning());
    } finally {
      consumer.stop();
    }
  }

  @Test
  void heartbeatCannotOverwriteARetryBackoff() throws Exception {
    var dispatchEntered = new CountDownLatch(1);
    var dispatchRelease = new CountDownLatch(1);
    var heartbeatEntered = new CountDownLatch(1);
    var heartbeatRelease = new CountDownLatch(1);
    var timeouts = new CopyOnWriteArrayList<Integer>();
    var receives = new AtomicInteger();
    var sqs = mock(SqsClient.class, CALLS_REAL_METHODS);
    doAnswer(
            call -> {
              if (receives.incrementAndGet() == 1)
                return ReceiveMessageResponse.builder()
                    .messages(
                        Message.builder()
                            .messageId("m")
                            .receiptHandle("r")
                            .body(SqsEventConsumerTest.validBody())
                            .build())
                    .build();
              new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
              return ReceiveMessageResponse.builder().build();
            })
        .when(sqs)
        .receiveMessage(any(ReceiveMessageRequest.class));
    doAnswer(
            call -> {
              ChangeMessageVisibilityRequest request = call.getArgument(0);
              if (request.visibilityTimeout() == 60) {
                heartbeatEntered.countDown();
                if (!heartbeatRelease.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("heartbeat not released");
              }
              timeouts.add(request.visibilityTimeout());
              return ChangeMessageVisibilityResponse.builder().build();
            })
        .when(sqs)
        .changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    var dispatcher = mock(ObservedDispatcher.class);
    when(dispatcher.dispatch(anyString(), any(EventEnvelope.class), anyInt()))
        .thenAnswer(
            call -> {
              dispatchEntered.countDown();
              if (!dispatchRelease.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("handler not released");
              return new EventDispatcher.Outcome(DeliveryDecision.RETRY, Duration.ofSeconds(3));
            });
    var consumer =
        new SqsEventConsumer(
            sqs,
            dispatcher,
            new AxizMessagingSqsProperties(
                List.of("queue"),
                Map.of("queue", "dlq"),
                Duration.ofSeconds(1),
                10,
                Duration.ofSeconds(60)),
            Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    consumer.start();
    try {
      assertTrue(dispatchEntered.await(5, TimeUnit.SECONDS));
      Thread heartbeat = Thread.ofVirtual().start(consumer::renewVisibility);
      assertTrue(heartbeatEntered.await(5, TimeUnit.SECONDS));
      dispatchRelease.countDown();
      Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> consumer.inFlightCount() == 0);
      assertTrue(timeouts.isEmpty(), "Backoff must wait for an already running heartbeat");
      heartbeatRelease.countDown();
      heartbeat.join(Duration.ofSeconds(5));
      Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> timeouts.size() == 2);
      consumer.renewVisibility();
      assertEquals(List.of(60, 1), timeouts);
    } finally {
      dispatchRelease.countDown();
      heartbeatRelease.countDown();
      consumer.stop();
    }
  }

  // --- AWS -----------------------------------------------------------------------------------

  /** Answers one message, then fails once, then has nothing more. */
  static final class ScriptedSqs implements SqsClient {
    final AtomicInteger receives = new AtomicInteger();
    int expectedBatchSize = 10;
    final List<String> settled = new CopyOnWriteArrayList<>();

    @Override
    public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
      assertEquals(expectedBatchSize, request.maxNumberOfMessages());
      int n = receives.incrementAndGet();
      if (n == 1) {
        return ReceiveMessageResponse.builder()
            .messages(
                Message.builder()
                    .messageId("m-1")
                    .receiptHandle("r-1")
                    .body(SqsEventConsumerTest.validBody())
                    .build())
            .build();
      }
      if (n == 2) {
        throw new IllegalStateException("throttled");
      }
      try {
        new CountDownLatch(1).await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return ReceiveMessageResponse.builder().messages(List.of()).build();
    }

    @Override
    public DeleteMessageResponse deleteMessage(Consumer<DeleteMessageRequest.Builder> request) {
      settled.add("delete");
      return DeleteMessageResponse.builder().build();
    }

    @Override
    public String serviceName() {
      return "sqs";
    }

    @Override
    public void close() {}
  }

  @Test
  void fifoPollingNeverAcquiresALaterGroupMemberWhileTheHeadIsInFlight() {
    var sqs = new ScriptedSqs();
    sqs.expectedBatchSize = 1;
    var handler = new SqsEventConsumerTest.Handler();
    var dispatcher =
        new EventDispatcher(
            "fifo",
            List.of(handler),
            SqsEventConsumerTest.CODEC,
            Deduplicator.none(),
            ConsumerRetryPolicy.defaults(),
            false);
    var consumer =
        new SqsEventConsumer(
            sqs,
            new ObservedDispatcher(dispatcher, ObservationRegistry.NOOP, Clock.systemUTC()),
            new AxizMessagingSqsProperties(
                List.of("https://sqs/queue.fifo"),
                Map.of("https://sqs/queue.fifo", "https://sqs/dlq.fifo"),
                Duration.ofSeconds(1),
                10,
                Duration.ofSeconds(60)),
            Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    consumer.start();
    try {
      Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !sqs.settled.isEmpty());
      assertEquals(List.of("hello"), handler.seen);
    } finally {
      consumer.stop();
    }
  }

  @Test
  void heartbeatRenewsEveryUnsettledReceiptDespiteFailuresAndShutdownPreservesInterrupt()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var renewals = new AtomicInteger();
    var attempts = new AtomicInteger();
    var sqs =
        new SqsClient() {
          @Override
          public String serviceName() {
            return "sqs";
          }

          @Override
          public void close() {}

          @Override
          public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
            if (attempts.incrementAndGet() > 1) return ReceiveMessageResponse.builder().build();
            return ReceiveMessageResponse.builder()
                .messages(
                    Message.builder()
                        .messageId("m1")
                        .receiptHandle("r1")
                        .body(SqsEventConsumerTest.validBody())
                        .build(),
                    Message.builder()
                        .messageId("m2")
                        .receiptHandle("r2")
                        .body(SqsEventConsumerTest.validBody())
                        .build())
                .build();
          }

          @Override
          public ChangeMessageVisibilityResponse changeMessageVisibility(
              Consumer<ChangeMessageVisibilityRequest.Builder> configure) {
            var builder = ChangeMessageVisibilityRequest.builder();
            configure.accept(builder);
            assertEquals(60, builder.build().visibilityTimeout());
            if (renewals.incrementAndGet() == 1)
              throw new IllegalStateException("synthetic outage");
            return ChangeMessageVisibilityResponse.builder().build();
          }

          @Override
          public DeleteMessageResponse deleteMessage(
              Consumer<DeleteMessageRequest.Builder> configure) {
            return DeleteMessageResponse.builder().build();
          }
        };
    var dispatcher = mock(ObservedDispatcher.class);
    when(dispatcher.dispatch(anyString(), any(EventEnvelope.class), anyInt()))
        .thenAnswer(
            call -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return new EventDispatcher.Outcome(DeliveryDecision.ACK, Duration.ZERO);
            });
    var consumer =
        new SqsEventConsumer(
            sqs,
            dispatcher,
            new AxizMessagingSqsProperties(
                List.of("https://sqs/q"),
                Map.of("https://sqs/q", "https://sqs/dlq"),
                Duration.ofSeconds(1),
                10,
                Duration.ofSeconds(60)),
            Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1)));
    consumer.start();
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(2, consumer.inFlightCount());
      consumer.renewVisibility();
      assertEquals(2, renewals.get());
      consumer.renewVisibility();
      assertEquals(4, renewals.get());
      Thread.currentThread().interrupt();
      org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, consumer::stop);
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(
          consumer.isRunning(),
          "Interrupted shutdown cannot report a still-running handler as stopped");
    } finally {
      Thread.interrupted();
      release.countDown();
      consumer.stop();
    }
    assertFalse(consumer.isRunning());
    assertEquals(0, consumer.inFlightCount());
  }

  @Test
  void theConsumerProcessesWhatArrivesAndKeepsPollingAfterAFailedReceive() throws Exception {
    ScriptedSqs sqs = new ScriptedSqs();
    SqsEventConsumerTest.Handler handler = new SqsEventConsumerTest.Handler();
    EventDispatcher dispatcher =
        new EventDispatcher(
            "c",
            List.of(handler),
            SqsEventConsumerTest.CODEC,
            Deduplicator.none(),
            ConsumerRetryPolicy.defaults(),
            false);
    SqsEventConsumer consumer =
        new SqsEventConsumer(
            sqs,
            new ObservedDispatcher(dispatcher, ObservationRegistry.NOOP, Clock.systemUTC()),
            new AxizMessagingSqsProperties(
                List.of("https://sqs.local/000000000000/payments"),
                Map.of("https://sqs.local/000000000000/payments", "https://sqs/dlq"),
                Duration.ofSeconds(20),
                10,
                Duration.ofSeconds(4)),
            Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1)));
    consumer.start();
    try {
      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .until(() -> sqs.receives.get() >= 3 && consumer.inFlightCount() == 0);
      assertTrue(sqs.receives.get() >= 3, "polling resumed after the failed receive");
      assertEquals(List.of("hello"), handler.seen);
      assertEquals(List.of("delete"), sqs.settled);
      assertEquals(0, consumer.inFlightCount());
      consumer.renewVisibility(); // nothing in flight: no call
    } finally {
      consumer.stop();
    }
    assertFalse(consumer.isRunning());
    assertEquals(Integer.MAX_VALUE - 100, consumer.getPhase());
  }
}
