package pe.axiz.payments.process.adapter.in.messaging;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.money.Money;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;
import pe.axiz.payments.process.application.usecase.StartPaymentProcessUseCase;
import pe.axiz.payments.process.domain.PaymentInstruction;

/**
 * Starts the payment process when payment-orders registers an order ({@code
 * payment-order.created.v1} on the {@code payments} topic). Redeliveries are harmless: the use case
 * returns the existing process. Business rejections end in the saga; only infrastructure failures
 * reach the consumer retries and its dead-letter topic.
 */
@Component
public class PaymentOrderCreatedHandler
    implements EventHandler<PaymentOrderCreatedHandler.PaymentOrderCreated> {

  static final String TYPE = "pe.axiz.payments.payment-order.created.v1";

  /**
   * Payload of the event (only the fields this service reads).
   *
   * @param orderId order
   * @param operationNumber operation number
   * @param debtorAccount account to debit
   * @param creditorCci destination account
   * @param amount amount
   * @param currency currency
   */
  public record PaymentOrderCreated(
      String orderId,
      String customerId,
      String operationNumber,
      @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String debtorAccount,
      @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String creditorCci,
      BigDecimal amount,
      String currency) {}

  private final StartPaymentProcessUseCase start;

  public PaymentOrderCreatedHandler(StartPaymentProcessUseCase start) {
    this.start = start;
  }

  @Override
  public String eventType() {
    return TYPE;
  }

  @Override
  public Class<PaymentOrderCreated> payloadType() {
    return PaymentOrderCreated.class;
  }

  @Override
  public void handle(PaymentOrderCreated e, EventEnvelope envelope) {
    start.execute(
        new StartPaymentProcessUseCase.Input(
            new PaymentInstruction(
                e.orderId(),
                e.operationNumber(),
                e.debtorAccount(),
                e.creditorCci(),
                Money.ofExact(e.amount(), e.currency()),
                e.customerId())));
  }
}
