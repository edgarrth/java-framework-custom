package pe.axiz.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.resilience.RetryPolicy;

class SagaObservationTest {
  @Test
  void confirmedStepAndCompensationNotificationsFollowDurableProgress() {
    for (boolean reject : List.of(false, true)) {
      var calls = new ArrayList<String>();
      var store = new InMemorySagaStateStore();
      var first = new SagaTest.Step("reserve", new ArrayList<>());
      var second = new SagaTest.Step("send", new ArrayList<>());
      if (reject) second.failExecute = 101;
      SagaListener listener =
          new SagaListener() {
            @Override
            public void stepCompleted(String name, String id, String step) {
              assertEquals("payment", name);
              assertEquals("operation", id);
              assertTrue(store.find(id).isPresent());
              calls.add("completed:" + step);
            }

            @Override
            public void compensated(String name, String id, String step) {
              calls.add("compensated:" + step);
            }

            @Override
            public void finished(String name, String id, SagaStatus status, Throwable cause) {
              assertEquals(status, store.find(id).orElseThrow().status());
              calls.add("finished:" + status);
            }
          };
      var orchestrator =
          new SagaOrchestrator(
              store, RetryPolicy.none(), RetryPolicy.none(), listener, Clock.systemUTC());
      var result =
          orchestrator.start(
              new SagaDefinition<>("payment", List.of(first, second)),
              new SagaTest.Codec(),
              "operation",
              new ArrayList<>());
      if (reject) {
        assertFalse(result.completed());
        assertEquals(
            List.of("completed:reserve", "compensated:reserve", "finished:COMPENSATED"), calls);
      } else {
        assertTrue(result.completed());
        assertEquals(List.of("completed:reserve", "completed:send", "finished:COMPLETED"), calls);
      }
    }
  }
}
