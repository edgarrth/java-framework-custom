package pe.axiz.core.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PublishResult;

class AsyncNotificationTest {
  @Test
  void wireIdentityAndCorrelationMatchImperativeSenderAndAcceptanceWaitsForBroker() {
    EventFactory factory =
        new EventFactory(
            "urn:service",
            "pe.axiz",
            new NotificationTest.JsonCodec(),
            () -> "unused",
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    List<EventEnvelope> sent = new ArrayList<>();
    var request =
        new NotificationRequest(
            "notification-1",
            NotificationChannel.EMAIL,
            "user@example.com",
            "account.created",
            Locale.ROOT,
            Map.of("amount", "10.00"),
            "corr");
    new EventNotificationSender(
            factory,
            (destination, event) -> {
              sent.add(event);
              return new PublishResult(event.id(), destination, null);
            },
            "notifications")
        .send(request);
    var pending = new CompletableFuture<PublishResult>();
    var sender =
        new AsyncEventNotificationSender(
            factory,
            (destination, event) -> {
              sent.add(event);
              assertEquals("notifications", destination);
              return pending;
            },
            "notifications");
    var result = sender.send(request).toCompletableFuture();
    assertFalse(result.isDone());
    assertEquals(CloudEventJson.write(sent.getFirst()), CloudEventJson.write(sent.getLast()));
    assertEquals("notification-1", sent.getLast().id());
    assertEquals("corr", sent.getLast().correlationId().orElseThrow());
    pending.complete(new PublishResult("notification-1", "notifications", "broker-id"));
    result.join();
    var withoutCorrelation =
        new NotificationRequest(
            "notification-2",
            NotificationChannel.SMS,
            "+51",
            "account.created",
            Locale.ROOT,
            Map.of(),
            null);
    new AsyncEventNotificationSender(
            factory,
            (destination, event) -> {
              assertTrue(event.correlationId().isEmpty());
              return CompletableFuture.completedFuture(
                  new PublishResult(event.id(), destination, null));
            },
            "notifications")
        .send(withoutCorrelation)
        .toCompletableFuture()
        .join();
  }

  @Test
  void cancellationReachesBrokerAndSubmissionErrorsRemainVisible() {
    var factory =
        new EventFactory(
            "urn:service", "", new NotificationTest.JsonCodec(), () -> "id", Clock.systemUTC());
    var request =
        new NotificationRequest(
            "n",
            NotificationChannel.PUSH,
            "device",
            "account.created",
            Locale.ROOT,
            Map.of(),
            null);
    var pending = new CompletableFuture<PublishResult>();
    var result =
        new AsyncEventNotificationSender(factory, (destination, event) -> pending, "notifications")
            .send(request)
            .toCompletableFuture();
    assertTrue(result.cancel(true));
    assertTrue(pending.isCancelled());
    var original = new IllegalStateException("unavailable");
    var rejected =
        new AsyncEventNotificationSender(
                factory,
                (destination, event) -> {
                  throw original;
                },
                "notifications")
            .send(request);
    assertSame(
        original,
        assertThrows(CompletionException.class, () -> rejected.toCompletableFuture().join())
            .getCause());
  }
}
