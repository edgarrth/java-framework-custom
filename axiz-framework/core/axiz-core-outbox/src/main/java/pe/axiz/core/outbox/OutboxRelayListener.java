package pe.axiz.core.outbox;

import java.time.Duration;

/** Instrumentación opcional del relay; el core no depende de un backend de métricas. */
public interface OutboxRelayListener {
  OutboxRelayListener NOOP =
      new OutboxRelayListener() {
        @Override
        public void onClaim(Duration duration, int rows) {}

        @Override
        public void onBudgetExhausted(int skipped) {}
      };

  void onClaim(Duration duration, int rows);

  void onBudgetExhausted(int skipped);
}
