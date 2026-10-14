package pe.axiz.payments.notifications.handler;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.notification.NotificationChannel;
import pe.axiz.core.notification.NotificationRequest;
import pe.axiz.payments.notifications.contact.CustomerContact;
import pe.axiz.payments.notifications.event.PaymentOrderStatusChanged;

class PaymentNotificationHandlerTest {

  final Map<String, CustomerContact> contacts = new HashMap<>();
  final List<NotificationRequest> sent = new ArrayList<>();
  final PaymentNotificationHandler handler =
      new PaymentNotificationHandler(
          id -> Optional.ofNullable(contacts.get(id)),
          sent::add,
          new EventMetrics(new SimpleMeterRegistry(), MetricsSettings.defaults()));

  static EventEnvelope envelope(String id) {
    return new EventEnvelope(
            id,
            "urn:axiz:payments-business",
            "pe.axiz.payments.payment-order.status-changed.v1",
            Instant.parse("2026-10-12T15:00:00Z"),
            "O-1",
            "application/json",
            new byte[0],
            Map.of())
        .withExtension(EventEnvelope.CORRELATION_ID, "corr-1");
  }

  static PaymentOrderStatusChanged changed(String status) {
    return new PaymentOrderStatusChanged("O-1", "C-1", "PROCESSING", status, "network rejected");
  }

  @Test
  void notifiesFinalStatusesThroughTheChosenChannel() throws Exception {
    contacts.put("C-1", new CustomerContact("C-1", "SMS", "+51987654321", "en", true));

    handler.handle(changed("REJECTED"), envelope("ev-1"));

    assertThat(sent).hasSize(1);
    NotificationRequest r = sent.get(0);
    assertThat(r.id()).isEqualTo("ev-1");
    assertThat(r.channel()).isEqualTo(NotificationChannel.SMS);
    assertThat(r.templateId()).isEqualTo("payment-rejected");
    assertThat(r.locale()).isEqualTo(Locale.ENGLISH);
    assertThat(r.parameters()).containsEntry("reason", "network rejected");
    assertThat(r.correlationId()).isEqualTo("corr-1");
  }

  @Test
  void intermediateStatusesAndOptOutsAreIgnored() throws Exception {
    contacts.put("C-1", new CustomerContact("C-1", "EMAIL", "a@example.com", null, false));

    handler.handle(changed("PROCESSING"), envelope("ev-2"));
    handler.handle(changed("COMPLETED"), envelope("ev-3"));

    assertThat(sent).isEmpty();
  }

  @Test
  void defaultLocaleIsSpanishPeru() throws Exception {
    contacts.put("C-1", new CustomerContact("C-1", "EMAIL", "a@example.com", null, true));

    handler.handle(changed("COMPLETED"), envelope("ev-4"));

    assertThat(sent.get(0).locale().toLanguageTag()).isEqualTo("es-PE");
    assertThat(sent.get(0).templateId()).isEqualTo("payment-completed");
  }
}
