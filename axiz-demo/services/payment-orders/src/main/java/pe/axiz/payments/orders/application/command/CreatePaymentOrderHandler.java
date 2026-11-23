package pe.axiz.payments.orders.application.command;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.core.money.Money;
import pe.axiz.core.time.CutoffTime;
import pe.axiz.extension.cqrs.CommandHandler;
import pe.axiz.payments.orders.application.port.out.OperationNumbers;
import pe.axiz.payments.orders.application.port.out.PaymentEvents;
import pe.axiz.payments.orders.application.port.out.PaymentOrders;
import pe.axiz.payments.orders.domain.PaymentErrors;
import pe.axiz.payments.orders.domain.PaymentLimits;
import pe.axiz.payments.orders.domain.PaymentOrder;

/**
 * Registra una orden de pago: valida el CCI del acreedor, asigna la fecha de procesamiento
 * (calendario hábil y hora de corte), reserva el monto en el límite diario del cliente, rechaza
 * probables duplicados, numera la operación y publica {@code payment-order.created} mediante el
 * outbox, todo en una sola transacción.
 *
 * <p>Concurrencia: la reserva es una actualización condicional atómica de la fila del cliente para
 * ese día y esa moneda, que queda bloqueada hasta el commit; la verificación de duplicados se
 * ejecuta después, de modo que dos órdenes concurrentes del mismo cliente y día se serializan y no
 * pueden exceder juntas el límite ni pasar ambas como no duplicadas. La clave de solicitud del
 * cliente es única por cliente: una solicitud reintentada devuelve la orden que ya creó
 * (idempotencia durable de la operación de negocio, independiente del almacén de idempotencia
 * HTTP).
 */
public class CreatePaymentOrderHandler
    implements CommandHandler<CreatePaymentOrderCommand, PaymentOrder> {

  private final PaymentOrders orders;
  private final OperationNumbers numbers;
  private final PaymentEvents events;
  private final PaymentLimits limits;
  private final CutoffTime cutoff;
  private final IdentifierRegistry identifiers;
  private final IdGenerator ids;
  private final EventMetrics metrics;
  private final Clock clock;

  public CreatePaymentOrderHandler(
      PaymentOrders orders,
      OperationNumbers numbers,
      PaymentEvents events,
      PaymentLimits limits,
      CutoffTime cutoff,
      IdentifierRegistry identifiers,
      IdGenerator ids,
      EventMetrics metrics,
      Clock clock) {
    this.orders = orders;
    this.numbers = numbers;
    this.events = events;
    this.limits = limits;
    this.cutoff = cutoff;
    this.identifiers = identifiers;
    this.ids = ids;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public PaymentOrder handle(CreatePaymentOrderCommand c) {
    if (!identifiers.isValid("cci-strict", c.creditorCci())) {
      throw new BusinessException(PaymentErrors.INVALID_CREDITOR_ACCOUNT, "invalid cci", Map.of());
    }
    // un monto solicitado nunca se redondea: más decimales de los que admite la moneda es un 400
    Money amount = Money.of(c.amount(), c.currency());
    String requestKey = c.requestKey();
    if (requestKey != null) {
      var existing = orders.findByRequestKey(c.customerId(), requestKey);
      if (existing.isPresent()) {
        return sameRequest(existing.get(), c, amount);
      }
    }
    LocalDate processingDate = cutoff.processingDate(clock.instant());
    Money limit = limits.limitFor(amount);
    if (amount.isGreaterThan(limit)
        || !orders.reserveDaily(c.customerId(), amount, processingDate, limit)) {
      throw limits.exceeded(
          limit, orders.reservedOn(c.customerId(), amount.currencyCode(), processingDate));
    }
    // tras la reserva: la fila del día del cliente está bloqueada y las órdenes concurrentes
    // esperan aquí
    if (orders.exists(c.customerId(), c.creditorCci(), amount, processingDate)) {
      throw new BusinessException(PaymentErrors.POSSIBLE_DUPLICATE, "possible duplicate", Map.of());
    }
    PaymentOrder order =
        PaymentOrder.register(
            ids.newId(),
            numbers.next(),
            c.customerId(),
            c.debtorAccount(),
            c.creditorCci(),
            amount,
            c.description(),
            processingDate,
            clock.instant());
    PaymentOrder persisted = orders.create(order, requestKey);
    events.created(persisted);
    metrics.record(
        MetricEvent.of("payment.order.created")
            .amount(amount)
            .outcome(Outcome.SUCCESS)
            .stage(cutoff.isBeforeCutoff(clock.instant()) ? "same-day" : "next-day"));
    return persisted;
  }

  /** Una solicitud reintentada devuelve su orden; la misma clave para otra orden es un error. */
  private static PaymentOrder sameRequest(
      PaymentOrder existing, CreatePaymentOrderCommand c, Money amount) {
    boolean same =
        existing.amount().equals(amount)
            && existing.creditorCci().equals(c.creditorCci())
            && existing.debtorAccount().equals(c.debtorAccount());
    if (!same) {
      throw new BusinessException(PaymentErrors.REQUEST_KEY_REUSED, "request key reused", Map.of());
    }
    return existing;
  }
}
