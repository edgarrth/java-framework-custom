package pe.axiz.payments.history.adapter.in.messaging;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;
import pe.axiz.extension.cqrs.spring.ReactiveCommandBus;
import pe.axiz.payments.history.application.command.RecordPaymentRegisteredCommand;
import pe.axiz.spring.messaging.ReactiveTypedEventHandler;

/**
 * Proyecta {@code payment-order.created.v1} (tópico {@code payments}, grupo de consumidores
 * payments-history). El comando se ejecuta mediante el pipeline reactivo (span, transacción sobre
 * R2DBC, métricas); el hilo del consumidor lo espera, de modo que los reintentos, el tópico de
 * mensajes fallidos (dead-letter) y el inbox R2DBC funcionan como en cualquier consumidor. Las
 * reentregas son inocuas: la proyección es idempotente.
 */
@Component
@HandlesEvent(PaymentOrderCreatedHandler.TYPE)
public class PaymentOrderCreatedHandler
    extends ReactiveTypedEventHandler<PaymentOrderCreatedHandler.PaymentOrderCreated> {

  static final String TYPE = "pe.axiz.payments.payment-order.created.v1";

  /**
   * Payload del evento (solo los campos que lee este servicio).
   *
   * @param orderId orden
   * @param operationNumber número de operación
   * @param customerId cliente
   * @param creditorCci cuenta de destino
   * @param amount monto
   * @param currency moneda
   * @param processingDate fecha de procesamiento
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
