package pe.axiz.payments.process.application.usecase;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.core.resilience.RetryPolicy;
import pe.axiz.core.saga.ContextCodec;
import pe.axiz.core.saga.InMemorySagaStateStore;
import pe.axiz.core.saga.SagaListener;
import pe.axiz.core.saga.SagaOrchestrator;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.payments.process.application.port.out.Accounts;
import pe.axiz.payments.process.application.port.out.PaymentOrders;
import pe.axiz.payments.process.application.port.out.Transfers;
import pe.axiz.payments.process.application.saga.PaymentContext;
import pe.axiz.payments.process.application.saga.PaymentSaga;
import pe.axiz.payments.process.domain.PaymentInstruction;

/**
 * El proceso de pago tal como lo cablea el servicio, sobre servicios aguas abajo que siempre
 * aceptan.
 */
public final class Processes {

  /** Cada llamada aguas abajo, en orden. */
  public final List<String> calls = new ArrayList<>();

  /** Estados de la saga. */
  public final SagaStateStore store = new InMemorySagaStateStore();

  /** El orquestador sobre {@link #store}. */
  public final SagaOrchestrator orchestrator =
      new SagaOrchestrator(
          store,
          RetryPolicy.transientFailures(3, Backoff.fixed(Duration.ZERO)),
          RetryPolicy.transientFailures(3, Backoff.fixed(Duration.ZERO)),
          SagaListener.none(),
          Clock.systemUTC());

  /** Conserva los contextos por referencia. */
  public final ContextCodec<PaymentContext> codec =
      new ContextCodec<>() {
        private final Map<String, PaymentContext> values = new HashMap<>();

        @Override
        public String encode(PaymentContext context) {
          String k = "c" + values.size();
          values.put(k, context);
          return k;
        }

        @Override
        public @Nullable PaymentContext decode(String text) {
          return values.get(text);
        }
      };

  private final Downstream downstream = new Downstream();

  /** La saga del servicio sobre los servicios aguas abajo que registran las llamadas. */
  public final pe.axiz.core.saga.SagaDefinition<PaymentContext> saga =
      PaymentSaga.definition(downstream, downstream, downstream);

  /** Inicia procesos. */
  public final StartPaymentProcessUseCase start =
      new StartPaymentProcessUseCase(
          orchestrator,
          store,
          saga,
          codec,
          new EventMetrics(new SimpleMeterRegistry(), MetricsSettings.defaults()));

  /** Lee procesos. */
  public final GetPaymentProcessUseCase get = new GetPaymentProcessUseCase(store);

  private final class Downstream implements PaymentOrders, Accounts, Transfers {
    @Override
    public void markProcessing(String orderId, String key) {
      calls.add("processing " + orderId);
    }

    @Override
    public void complete(String orderId, String key) {
      calls.add("complete " + orderId);
    }

    @Override
    public void reject(String orderId, String reason, String key) {
      calls.add("reject " + orderId);
    }

    @Override
    public String debit(PaymentInstruction instruction, String key) {
      calls.add("debit " + instruction.debtorAccount() + " " + instruction.amount());
      return "MV-1";
    }

    @Override
    public void reverse(String movementId, String key) {
      calls.add("reverse " + movementId);
    }

    @Override
    public String send(PaymentInstruction instruction, String key) {
      calls.add("transfer " + instruction.creditorCci());
      return "NET-1";
    }
  }
}
