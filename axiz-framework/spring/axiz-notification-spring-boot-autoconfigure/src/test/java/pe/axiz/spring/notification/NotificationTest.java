package pe.axiz.spring.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.notification.NotificationChannel;
import pe.axiz.core.notification.NotificationRequest;
import pe.axiz.core.notification.NotificationSender;

class NotificationTest {

  @Test
  void notificationsArePublishedAsEvents() {
    List<String> sent = new CopyOnWriteArrayList<>();
    PayloadCodec codec =
        new PayloadCodec() {
          @Override
          public String contentType() {
            return "text/plain";
          }

          @Override
          public byte[] encode(Object payload) {
            return payload.toString().getBytes(StandardCharsets.UTF_8);
          }

          @Override
          public <T> T decode(byte[] data, Class<T> type) {
            throw new UnsupportedOperationException();
          }
        };
    EventPublisher publisher =
        new EventPublisher() {
          @Override
          public PublishResult publish(String destination, EventEnvelope event) {
            sent.add(destination + "|" + event.type());
            return new PublishResult(event.id(), destination, null);
          }

          @Override
          public boolean supports(String destination) {
            return true;
          }
        };
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizNotificationAutoConfiguration.class))
        .withBean(
            EventFactory.class,
            () ->
                new EventFactory(
                    "urn:axiz:t", "pe.axiz.", codec, IdGenerator.uuidV7(), Clock.systemUTC()))
        .withBean(EventPublisher.class, () -> publisher)
        .run(
            ctx ->
                ctx.getBean(NotificationSender.class)
                    .send(
                        new NotificationRequest(
                            "n-1",
                            NotificationChannel.SMS,
                            "+51999999999",
                            "payment.approved",
                            Locale.forLanguageTag("es-PE"),
                            Map.of("amount", "10.00"),
                            null)));
    assertEquals(List.of("notifications|pe.axiz.notification.requested.v1"), sent);
  }
}
