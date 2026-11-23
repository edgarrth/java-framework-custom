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
 * La saga de pago.
 *
 * <ol>
 *   <li>{@code processing}: la orden pasa a PROCESSING; deshacer: la orden queda REJECTED.
 *   <li>{@code debit}: se debita la cuenta deudora; deshacer: se revierte el débito.
 *   <li>{@code transfer}: se envía la transferencia. Es el pivote: una vez que la red la acepta, no
 *       se deshace nada.
 *   <li>{@code complete}: la orden pasa a COMPLETED. Nunca hace fallar la saga (el dinero ya se
 *       movió): si payment-orders no está accesible, el contexto se marca con {@code
 *       completionPending} y la conciliación de liquidación completa la orden.
 * </ol>
 *
 * <p>Cada llamada aguas abajo usa una clave derivada de la orden y del paso, por lo que los
 * reintentos y las sagas reanudadas nunca repiten un efecto. Un paso que falla sin un rechazo
 * definitivo se verifica (la misma llamada con la misma clave) previo a deshacer cualquier cosa: un
 * débito confirmado tras un timeout nunca queda vigente bajo una orden "rechazada", y una
 * transferencia cuya respuesta se perdió nunca provoca la reversión del débito.
 */
public final class PaymentSaga {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentSaga.class);

  public static final String NAME = "payment";

  /** Motivo que se envía a payment-orders cuando se deshace el pago. */
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
                        "Order {} paid but not completed; settlement reconciliation will do it; failureType={}",
                        c.instruction().orderId(),
                        e.getClass().getSimpleName());
                    return c.pendingCompletion();
                  }
                },
                false,
                _ -> {})));
  }

  /** Acción del paso. */
  @FunctionalInterface
  interface Action {
    PaymentContext apply(PaymentContext context) throws Exception;
  }

  /** Compensación del paso. */
  @FunctionalInterface
  interface Undo {
    void apply(PaymentContext context) throws Exception;
  }

  /**
   * Un paso. Con {@code verifiable}, una falla incierta (timeout, 5xx, respuesta perdida) se
   * verifica repitiendo la llamada con la misma clave de idempotencia: cada participante
   * (payment-orders, banking-core y, a través de este, el core y la red) devuelve el primer
   * resultado para una clave que ya procesó y aplica la solicitud si nunca llegó. Un rechazo
   * observado durante la verificación no prueba que una llamada previa en curso no pueda aplicarse
   * nunca: sin un contrato durable de cancelación del participante, el estado sigue siendo
   * IN_DOUBT.
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
