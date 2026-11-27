package pe.axiz.payments.history.application.access;

import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.extension.cqrs.ReactiveAccessPolicy;
import pe.axiz.payments.history.application.port.out.PaymentHistories;
import pe.axiz.payments.history.application.query.GetPaymentQuery;
import pe.axiz.payments.history.application.query.ListCustomerPaymentsQuery;
import reactor.core.publisher.Mono;

/**
 * Quién lee el historial de pagos: el cliente, sus propios pagos; los servicios con {@code
 * payments:read}, cualquiera de ellos. El pago de otro cliente se informa como no encontrado.
 */
public final class HistoryAccess {

  public static final String READ = "SCOPE_payments:read";

  private HistoryAccess() {}

  static boolean canRead(Caller caller, String customerId) {
    return caller.isMachine() ? caller.hasAuthority(READ) : caller.isUser(customerId);
  }

  /** Los clientes listan su propio historial. */
  public static class ListPolicy implements ReactiveAccessPolicy<ListCustomerPaymentsQuery> {

    @Override
    public Class<ListCustomerPaymentsQuery> requestType() {
      return ListCustomerPaymentsQuery.class;
    }

    @Override
    public Mono<Void> check(ListCustomerPaymentsQuery request, Caller caller) {
      return Mono.defer(
          () ->
              canRead(caller, request.customerId())
                  ? Mono.empty()
                  : Mono.error(
                      new AccessException(CoreErrors.FORBIDDEN, "history of another customer")));
    }
  }

  /**
   * Un pago, solo para su cliente. No bloqueante: el propietario se lee mediante el repositorio
   * reactivo, nunca con {@code block()}.
   */
  public static class GetPolicy implements ReactiveAccessPolicy<GetPaymentQuery> {

    private final PaymentHistories histories;

    public GetPolicy(PaymentHistories histories) {
      this.histories = histories;
    }

    @Override
    public Class<GetPaymentQuery> requestType() {
      return GetPaymentQuery.class;
    }

    @Override
    public Mono<Void> check(GetPaymentQuery request, Caller caller) {
      return histories
          .find(request.paymentId())
          .filter(payment -> canRead(caller, payment.customerId()))
          .switchIfEmpty(Mono.error(new NotFoundException("payment", request.paymentId())))
          .then();
    }
  }
}
