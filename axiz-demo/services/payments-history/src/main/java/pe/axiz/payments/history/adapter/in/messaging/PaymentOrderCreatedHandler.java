package pe.axiz.payments.history.adapter.in.messaging;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;
import pe.axiz.framework.cqrs.spring.ReactiveCommandBus;
import pe.axiz.framework.eda.HandlesEvent;
import pe.axiz.framework.eda.ReactiveTypedEventHandler;
import pe.axiz.payments.history.application.command.RecordPaymentRegisteredCommand;

/**
 * Projects {@code payment-order.created.v1} (topic {@code payments}, consumer group
 * payments-history). The command runs through the reactive pipeline (span, transaction on R2DBC,
 * metrics); the consumer thread waits for it, so retries, the dead-letter topic and the R2DBC inbox
 * work as for any consumer. Redeliveries are harmless: the projection is idempotent.
 */
@Component
@HandlesEvent(PaymentOrderCreatedHandler.TYPE)
public class PaymentOrderCreatedHandler
    extends ReactiveTypedEventHandler<PaymentOrderCreatedHandler.PaymentOrderCreated> {

  static final String TYPE = "pe.axiz.payments.payment-order.created.v1";

  /**
   * Payload of the event (only the fields this service reads).
   *
   * @param orderId order
   * @param operationNumber operation number
   * @param customerId customer
   * @param creditorCci destination account
   * @param amount amount
   * @param currency currency
   * @param processingDate processing date
   */
  public record PaymentOrderCreated(
      String orderId,
      String operationNumber,
      String customerId,
      @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String creditorCci,
      BigDecimal amount,
      String currency,
      LocalDate processingDate) {}

  private final ReactiveCommandBus commands;

  public PaymentOrderCreatedHandler(ReactiveCommandBus commands) {
    this.commands = commands;
  }

  @Override
  protected Publisher<Void> on(PaymentOrderCreated e, EventEnvelope envelope) {
    return commands.send(
        new RecordPaymentRegisteredCommand(
            e.orderId(),
            e.operationNumber(),
            e.customerId(),
            e.creditorCci(),
            e.amount(),
            e.currency(),
            e.processingDate(),
            envelope.time()));
  }
}
