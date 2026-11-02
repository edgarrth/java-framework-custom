package pe.axiz.spring.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.commons.json.JsonWriter;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.notification.AsyncNotificationSender;
import pe.axiz.core.notification.NotificationChannel;
import pe.axiz.core.notification.NotificationRequest;
import pe.axiz.core.notification.NotificationSender;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@ExtendWith(BlockHoundExtension.class)
class NativeNotificationTest {
  @BeforeAll
  static void warm() {
    ReactiveInfrastructureBootstrap.initialize();
  }

  @AfterEach
  void noBlocking() {
    assertThat(BlockHoundExtension.detectedBlockingCalls()).isEmpty();
  }

  @Test
  void nativePublicationIsDeferredKeepsIdentityAndCancelsTheBrokerOperation() throws Exception {
    List<EventEnvelope> sent = new CopyOnWriteArrayList<>();
    CountDownLatch submitted = new CountDownLatch(1);
    CountDownLatch cancelled = new CountDownLatch(1);
    CompletableFuture<PublishResult> pending =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean done = super.cancel(interrupt);
            cancelled.countDown();
            return done;
          }
        };
    runner()
        .withBean(
            AsyncEventPublisher.class,
            () ->
                (destination, event) -> {
                  assertEquals("notifications", destination);
                  sent.add(event);
                  submitted.countDown();
                  return pending;
                })
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().doesNotHaveBean(NotificationSender.class);
              var work = ctx.getBean(ReactiveNotifications.class).send(request());
              assertTrue(sent.isEmpty());
              StepVerifier.create(work.subscribeOn(Schedulers.parallel()))
                  .then(() -> await(submitted))
                  .thenCancel()
                  .verify(Duration.ofSeconds(5));
              await(cancelled);
              assertTrue(pending.isCancelled());
            });
    assertEquals(1, sent.size());
    assertEquals("notification-1", sent.getFirst().id());
    assertEquals("pe.axiz.notification.requested.v1", sent.getFirst().type());
    assertEquals("corr", sent.getFirst().correlationId().orElseThrow());
  }

  @Test
  void missingNativePublisherRejectsImperativeFallbackAndDisableAndOverridesWork() {
    runner()
        .withBean(
            EventPublisher.class,
            () ->
                (destination, event) -> {
                  throw new AssertionError("imperative fallback");
                })
        .run(ctx -> assertThat(ctx).hasFailed());
    runner()
        .withPropertyValues("axiz.notification.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(ReactiveNotifications.class));
    AtomicInteger sends = new AtomicInteger();
    runner()
        .withBean(
            AsyncNotificationSender.class,
            () ->
                request -> {
                  sends.incrementAndGet();
                  return CompletableFuture.completedFuture(null);
                })
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              StepVerifier.create(
                      ctx.getBean(ReactiveNotifications.class)
                          .send(request())
                          .subscribeOn(Schedulers.parallel()))
                  .expectComplete()
                  .verify(Duration.ofSeconds(5));
            });
    assertEquals(1, sends.get());
  }

  private static ReactiveWebApplicationContextRunner runner() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizNotificationAutoConfiguration.class))
        .withBean(EventFactory.class, NativeNotificationTest::factory);
  }

  private static NotificationRequest request() {
    return new NotificationRequest(
        "notification-1",
        NotificationChannel.SMS,
        "+51999999999",
        "account.created",
        Locale.ROOT,
        Map.of("amount", "10.00"),
        "corr");
  }

  private static EventFactory factory() {
    PayloadCodec codec =
        new PayloadCodec() {
          public String contentType() {
            return "application/json";
          }

          public byte[] encode(Object value) {
            return JsonWriter.write(value).getBytes(StandardCharsets.UTF_8);
          }

          public <T> T decode(byte[] bytes, Class<T> type) {
            throw new UnsupportedOperationException();
          }
        };
    return new EventFactory("urn:service", "pe.axiz", codec, () -> "unused", Clock.systemUTC());
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException error) {
      throw new AssertionError(error);
    }
  }
}
