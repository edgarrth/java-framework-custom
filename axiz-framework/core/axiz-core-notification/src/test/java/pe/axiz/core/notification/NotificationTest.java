package pe.axiz.core.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.messaging.PublishResult;

class NotificationTest {

  static final class JsonCodec implements PayloadCodec {
    @Override
    public String contentType() {
      return "application/json";
    }

    @Override
    public byte[] encode(Object payload) {
      return JsonWriter.write(payload).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T decode(byte[] data, Class<T> type) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void rendersAndEscapes() {
    assertEquals(
        "Hola Ana, tu código es 123",
        TemplateRenderer.render(
            "Hola {{name}}, tu código es {{ code }}",
            Map.of("name", "Ana", "code", "123"),
            NotificationChannel.SMS));
    assertEquals(
        "<b>&lt;script&gt;</b>",
        TemplateRenderer.render(
            "<b>{{x}}</b>", Map.of("x", "<script>"), NotificationChannel.EMAIL));
    assertThrows(
        IllegalArgumentException.class,
        () -> TemplateRenderer.render("{{missing}}", Map.of(), NotificationChannel.PUSH));
    assertEquals("&amp;&quot;&#39;", TemplateRenderer.html("&\"'"));
  }

  @Test
  void publishesNotificationEvent() {
    List<EventEnvelope> sent = new ArrayList<>();
    EventFactory factory =
        new EventFactory(
            "urn:axiz:payments",
            "pe.axiz",
            new JsonCodec(),
            IdGenerator.uuidV7(),
            Clock.systemUTC());
    EventNotificationSender sender =
        new EventNotificationSender(
            factory,
            (destination, event) -> {
              sent.add(event);
              return new PublishResult(event.id(), destination, null);
            },
            "arn:aws:sns:us-east-1:1:notifications");
    sender.send(
        new NotificationRequest(
            "n-1",
            NotificationChannel.EMAIL,
            "ana@example.com",
            "payment.confirmed",
            Locale.forLanguageTag("es-PE"),
            Map.of("amount", "PEN 10.00"),
            "c-1"));
    EventEnvelope e = sent.get(0);
    assertEquals("pe.axiz.notification.requested.v1", e.type());
    assertEquals("n-1", e.id());
    assertEquals("c-1", e.correlationId().orElseThrow());
    assertTrue(
        new String(e.data(), StandardCharsets.UTF_8)
            .contains("\"templateId\":\"payment.confirmed\""));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NotificationRequest(
                "n", NotificationChannel.SMS, "+51", "Bad Template", Locale.ROOT, Map.of(), null));
  }
}
