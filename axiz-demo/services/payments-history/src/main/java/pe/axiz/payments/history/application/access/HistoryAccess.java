package pe.axiz.payments.history.application.access;

import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.framework.cqrs.AccessPolicy;
import pe.axiz.framework.cqrs.Caller;
import pe.axiz.framework.cqrs.ReactiveAccessPolicy;
import pe.axiz.payments.history.application.port.out.PaymentHistories;
import pe.axiz.payments.history.application.query.GetPaymentQuery;
import pe.axiz.payments.history.application.query.ListCustomerPaymentsQuery;
import reactor.core.publisher.Mono;

/**
 * Who reads the payment history: the customer its own payments, services with {@code payments:read}
 * any of them. Another customer's payment is reported as not found.
 */
public final class HistoryAccess {

  public static final String READ = "SCOPE_payments:read";

  private HistoryAccess() {}

  static boolean canRead(Caller caller, String customerId) {
    return caller.isMachine() ? caller.hasAuthority(READ) : caller.isUser(customerId);
  }

  /** Customers list their own history. */
  public static class ListPolicy implements AccessPolicy<ListCustomerPaymentsQuery> {

    @Override
    public Class<ListCustomerPaymentsQuery> requestType() {
      return ListCustomerPaymentsQuery.class;
    }

    @Override
    public void check(ListCustomerPaymentsQuery request, Caller caller) {
      if (!canRead(caller, request.customerId())) {
        throw new AccessException(CoreErrors.FORBIDDEN, "history of another customer");
      }
    }
  }

  /**
   * One payment, only for its customer. Non-blocking: the owner is read through the reactive
   * repository, never with {@code block()}.
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
