package pe.axiz.core.outbox;

import java.time.Duration;

/** Optional relay instrumentation; core has no dependency on a metrics backend. */
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
