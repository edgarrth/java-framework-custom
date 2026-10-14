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
import pe.axiz.core.saga.SagaStep;

class SagaTest {

  record Step(String name, List<String> log, boolean fail) implements SagaStep<String> {
    @Override
    public String execute(String context) {
      if (fail) {
        throw new IllegalArgumentException("rejected by " + name);
      }
      log.add("do:" + name);
      return context + ">" + name;
    }

    @Override
    public void compensate(String context) {
      log.add("undo:" + name);
    }
  }

  @Test
  void failedStepCompensatesPreviousOnes() {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:saga;DB_CLOSE_DELAY=-1");
    List<String> log = new CopyOnWriteArrayList<>();
    ContextCodec<String> codec =
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
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSagaAutoConfiguration.class))
        .withBean(DataSource.class, () -> h2)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            ctx -> {
              SagaOrchestrator orchestrator = ctx.getBean(SagaOrchestrator.class);
              SagaDefinition<String> transfer =
                  new SagaDefinition<>(
                      "transfer",
                      List.of(new Step("debit", log, false), new Step("credit", log, true)));
              SagaResult<String> result = orchestrator.start(transfer, codec, "s-1", "start");
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
}
