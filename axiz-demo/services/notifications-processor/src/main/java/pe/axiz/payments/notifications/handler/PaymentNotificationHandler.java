package pe.axiz.payments.notifications.handler;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.core.messaging.TypedEventHandler;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.core.notification.NotificationChannel;
import pe.axiz.core.notification.NotificationRequest;
import pe.axiz.core.notification.NotificationSender;
import pe.axiz.payments.notifications.event.PaymentOrderStatusChanged;
import pe.axiz.payments.notifications.repository.ContactDirectory;
import pe.axiz.payments.notifications.repository.CustomerContact;

/**
 * Tells the customer how a payment ended (completed or rejected) through the channel they chose.
 * The notification request goes to the notifications platform as an event on SNS; its id is the id
 * of the source event, so a redelivered event never notifies twice downstream either.
 */
@Component
@HandlesEvent("pe.axiz.payments.payment-order.status-changed.v1")
public class PaymentNotificationHandler extends TypedEventHandler<PaymentOrderStatusChanged> {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentNotificationHandler.class);
  static final Set<String> FINAL = Set.of("COMPLETED", "REJECTED");
  static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("es-PE");

  private final ContactDirectory contacts;
  private final NotificationSender sender;
  private final EventMetrics metrics;

  public PaymentNotificationHandler(
      ContactDirectory contacts, NotificationSender sender, EventMetrics metrics) {
    this.contacts = contacts;
    this.sender = sender;
    this.metrics = metrics;
  }

  @Override
  protected void on(PaymentOrderStatusChanged e, EventEnvelope envelope) {
    if (!FINAL.contains(e.status())) {
      return;
    }
    Optional<CustomerContact> contact =
        contacts.find(e.customerId()).filter(CustomerContact::isPaymentAlerts);
    if (contact.isEmpty()) {
      metrics.record(
          MetricEvent.of("notification.payment").outcome(Outcome.CANCELLED).reason("no-contact"));
      LOG.info("No payment alerts for order {}: customer has no active contact", e.orderId());
      return;
    }
    CustomerContact c = contact.get();
    NotificationChannel channel = NotificationChannel.valueOf(c.getChannel());
    String languageTag = c.getLocale();
    String reason = e.reason();
    sender.send(
        new NotificationRequest(
            envelope.id(),
            channel,
            c.getAddress(),
            "payment-" + e.status().toLowerCase(Locale.ROOT),
            languageTag == null ? DEFAULT_LOCALE : Locale.forLanguageTag(languageTag),
            Map.of("orderId", e.orderId(), "reason", reason == null ? "" : reason),
            envelope.correlationId().orElse(null)));
    metrics.record(
        MetricEvent.of("notification.payment")
            .tag("medium", channel.name().toLowerCase(Locale.ROOT))
            .stage(e.status().toLowerCase(Locale.ROOT))
            .outcome(Outcome.SUCCESS));
  }
}
