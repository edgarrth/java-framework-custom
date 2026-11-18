package pe.axiz.spring.messaging.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsConsumerLifecycleTest {
  // --- AWS -----------------------------------------------------------------------------------

  /** Answers one message, then fails once, then has nothing more. */
  static final class ScriptedSqs implements SqsClient {
    final AtomicInteger receives = new AtomicInteger();
    final List<String> settled = new CopyOnWriteArrayList<>();

    @Override
    public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
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
                Map.of(),
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
      consumer.stop();
      assertTrue(Thread.currentThread().isInterrupted());
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
                Map.of(),
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
