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
 * Inicia el proceso de pago cuando payment-orders registra una orden ({@code
 * payment-order.created.v1} en el tópico {@code payments}). Las reentregas son inocuas: el caso de
 * uso devuelve el proceso existente. Los rechazos de negocio terminan en la saga; solo las fallas
 * de infraestructura llegan a los reintentos del consumidor y a su tópico de mensajes fallidos
 * (dead-letter).
 */
@Component
public class PaymentOrderCreatedHandler
    implements EventHandler<PaymentOrderCreatedHandler.PaymentOrderCreated> {

  static final String TYPE = "pe.axiz.payments.payment-order.created.v1";

  /**
   * Payload del evento (solo los campos que lee este servicio).
   *
   * @param orderId orden
   * @param operationNumber número de operación
   * @param debtorAccount cuenta que se debita
   * @param creditorCci cuenta de destino
   * @param amount monto
   * @param currency moneda
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
                Money.of(e.amount(), e.currency()),
                e.customerId())));
  }
}
