package pe.axiz.spring.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.saga.ContextCodec;
import pe.axiz.core.saga.SagaDefinition;
import pe.axiz.core.saga.SagaOrchestrator;
import pe.axiz.core.saga.SagaResult;
import pe.axiz.core.saga.SagaStatus;
import pe.axiz.core.saga.SagaStep;
import pe.axiz.core.saga.SagaStepRejectedException;

class SagaTest {

  /**
   * How a step ends: it applies, the participant rejects it for sure, or its outcome is unknown.
   */
  enum Outcome {
    APPLIED,
    REJECTED,
    UNKNOWN
  }

  record Step(String name, List<String> log, Outcome outcome) implements SagaStep<String> {
    @Override
    public String execute(String context) {
      if (outcome == Outcome.REJECTED) {
        throw new SagaStepRejectedException("rejected by " + name, null);
      }
      if (outcome == Outcome.UNKNOWN) {
        // e.g. a timeout: the effect may exist, so nothing may be compensated
        throw new IllegalStateException("no answer from " + name);
      }
      log.add("do:" + name);
      return context + ">" + name;
    }

    @Override
    public void compensate(String context) {
      log.add("undo:" + name);
    }
  }

  private static final ContextCodec<String> CODEC =
      new ContextCodec<>() {
        @Override
        public String encode(String context) {
          return context;
        }

        @Override
        public String decode(String text) {
          return text;
        }
      };

  private static ApplicationContextRunner runner(String database) {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSagaAutoConfiguration.class))
        .withBean(DataSource.class, () -> h2)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
  }

  @Test
  void rejectedStepCompensatesPreviousOnes() {
    List<String> log = new CopyOnWriteArrayList<>();
    runner("saga-rejected")
        .run(
            ctx -> {
              SagaOrchestrator orchestrator = ctx.getBean(SagaOrchestrator.class);
              SagaDefinition<String> transfer =
                  new SagaDefinition<>(
                      "transfer",
                      List.of(
                          new Step("debit", log, Outcome.APPLIED),
                          new Step("credit", log, Outcome.REJECTED)));
              SagaResult<String> result = orchestrator.start(transfer, CODEC, "s-1", "start");
              assertTrue(!result.completed());
              assertEquals(List.of("do:debit", "undo:debit"), log);
              assertEquals(
                  1.0,
                  ctx.getBean(MeterRegistry.class)
                      .get("axiz.saga.compensations")
                      .counter()
                      .count());
            });
  }

  @Test
  void uncertainStepLeavesTheSagaInDoubtWithoutCompensating() {
    List<String> log = new CopyOnWriteArrayList<>();
    runner("saga-in-doubt")
        .run(
            ctx -> {
              SagaOrchestrator orchestrator = ctx.getBean(SagaOrchestrator.class);
              SagaDefinition<String> transfer =
                  new SagaDefinition<>(
                      "transfer",
                      List.of(
                          new Step("debit", log, Outcome.APPLIED),
                          new Step("credit", log, Outcome.UNKNOWN)));
              SagaResult<String> result = orchestrator.start(transfer, CODEC, "s-2", "start");
              assertEquals(SagaStatus.IN_DOUBT, result.status());
              assertEquals("credit", result.failedStep());
              assertEquals(List.of("do:debit"), log);
            });
  }
}
