package pe.axiz.payments.process.application.saga;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.saga.SagaDefinition;
import pe.axiz.core.saga.SagaStep;
import pe.axiz.core.saga.StepCheck;
import pe.axiz.core.saga.StepFailures;
import pe.axiz.payments.process.application.port.out.Accounts;
import pe.axiz.payments.process.application.port.out.PaymentOrders;
import pe.axiz.payments.process.application.port.out.Transfers;

/**
 * The payment saga.
 *
 * <ol>
 *   <li>{@code processing}: the order moves to PROCESSING; undo: the order is REJECTED.
 *   <li>{@code debit}: the debtor account is debited; undo: the debit is reversed.
 *   <li>{@code transfer}: the transfer is sent. This is the pivot: once the network accepts it
 *       nothing is undone.
 *   <li>{@code complete}: the order moves to COMPLETED. It never fails the saga (the money already
 *       moved): if payment-orders cannot be reached the context is marked {@code completionPending}
 *       and the settlement reconciliation completes the order.
 * </ol>
 *
 * <p>Every downstream call uses a key derived from the order and the step, so retries and resumed
 * sagas never repeat an effect. A step that fails without a definite rejection is verified (the
 * same call with the same key) before anything is undone: a debit confirmed after a timeout is
 * never left in place under a "rejected" order, and a transfer whose answer was lost never leads to
 * reversing the debit.
 */
public final class PaymentSaga {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentSaga.class);

  public static final String NAME = "payment";

  /** Reason sent to payment-orders when the payment is undone. */
  static final String REJECTION_REASON = "payment could not be completed";

  private PaymentSaga() {}

  public static SagaDefinition<PaymentContext> definition(
      PaymentOrders orders, Accounts accounts, Transfers transfers) {
    return new SagaDefinition<>(
        NAME,
        List.of(
            step(
                "processing",
                c -> {
                  orders.markProcessing(
                      c.instruction().orderId(), c.instruction().keyFor("processing"));
                  return c;
                },
                true,
                c ->
                    orders.reject(
                        c.instruction().orderId(),
                        REJECTION_REASON,
                        c.instruction().keyFor("reject"))),
            step(
                "debit",
                c -> c.debited(accounts.debit(c.instruction(), c.instruction().keyFor("debit"))),
                true,
                c -> {
                  if (c.movementId() != null) {
                    accounts.reverse(c.movementId(), c.instruction().keyFor("reverse"));
                  }
                }),
            step(
                "transfer",
                c -> c.sent(transfers.send(c.instruction(), c.instruction().keyFor("transfer"))),
                true,
                _ -> {}),
            step(
                "complete",
                c -> {
                  try {
                    orders.complete(c.instruction().orderId(), c.instruction().keyFor("complete"));
                    return c;
                  } catch (RuntimeException e) {
                    LOG.error(
                        "Order {} paid but not completed; settlement reconciliation will do it",
                        c.instruction().orderId(),
                        e);
                    return c.pendingCompletion();
                  }
                },
                false,
                _ -> {})));
  }

  /** Step action. */
  @FunctionalInterface
  interface Action {
    PaymentContext apply(PaymentContext context) throws Exception;
  }

  /** Step compensation. */
  @FunctionalInterface
  interface Undo {
    void apply(PaymentContext context) throws Exception;
  }

  /**
   * A step. With {@code verifiable}, an uncertain failure (timeout, 5xx, lost answer) is verified
   * by repeating the call with the same idempotency key: every participant (payment-orders,
   * banking-core and through it the core and the network) returns the first outcome for a key it
   * already processed, and applies the request if it never arrived. A definite rejection means the
   * effect does not exist; another failure leaves the process VERIFYING.
   */
  static SagaStep<PaymentContext> step(String name, Action action, boolean verifiable, Undo undo) {
    return new SagaStep<>() {
      @Override
      public StepCheck<PaymentContext> verify(PaymentContext context) {
        if (!verifiable) {
          return StepCheck.unknown();
        }
        try {
          return StepCheck.applied(action.apply(context));
        } catch (Exception e) {
          return StepFailures.isDefiniteRejection(e) ? StepCheck.notApplied() : StepCheck.unknown();
        }
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public PaymentContext execute(PaymentContext context) throws Exception {
        return action.apply(context);
      }

      @Override
      public void compensate(PaymentContext context) throws Exception {
        undo.apply(context);
      }
    };
  }
}
