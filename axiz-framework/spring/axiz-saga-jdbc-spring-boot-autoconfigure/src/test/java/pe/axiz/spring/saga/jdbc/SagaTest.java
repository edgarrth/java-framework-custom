package pe.axiz.spring.saga.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.saga.ContextCodec;
import pe.axiz.core.saga.SagaDefinition;
import pe.axiz.core.saga.SagaOrchestrator;
import pe.axiz.core.saga.SagaResult;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.core.saga.SagaStatus;
import pe.axiz.core.saga.SagaStep;
import pe.axiz.core.saga.SagaStepRejectedException;
import pe.axiz.spring.saga.AxizSagaAutoConfiguration;

class SagaTest {

  @Test
  void persistedContextIsEncryptedAndCannotBeReadAfterProtectionIsDisabled() {
    var keys =
        new DataKeyProvider() {
          public DataKey generate(String context) {
            return new DataKey(new byte[32], new byte[] {1});
          }

          public byte[] decrypt(byte[] key, String context) {
            return new byte[32];
          }
        };
    var definition =
        new SagaDefinition<String>(
            "protected-saga",
            List.of(new Step("save", new CopyOnWriteArrayList<>(), Outcome.APPLIED)));
    runner("saga-encrypted")
        .withPropertyValues("axiz.saga.context-protection=envelope")
        .withBean(
            EnvelopeEncryptor.class,
            () -> new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC()))
        .run(
            ctx -> {
              ctx.getBean(SagaOrchestrator.class).start(definition, CODEC, "s-private", "00123456");
              var persisted = ctx.getBean(SagaStateStore.class).find("s-private").orElseThrow();
              assertTrue(persisted.context().startsWith("axzp1:"));
              assertFalse(persisted.context().contains("00123456"));
            });
    runner("saga-encrypted")
        .run(
            ctx ->
                assertThrows(
                    CryptoException.class,
                    () ->
                        ctx.getBean(SagaOrchestrator.class)
                            .resume(definition, CODEC, "s-private")));
  }

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
        .withConfiguration(
            AutoConfigurations.of(
                AxizSagaAutoConfiguration.class, AxizSagaJdbcAutoConfiguration.class))
        .withBean(DataSource.class, () -> h2)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
  }

  /**
   * Use cases and transactional event handlers call the orchestrator inside an application
   * transaction: the store must commit its state on its own connection, so another worker sees it
   * at once and a later rollback of the caller does not erase it.
   */
  @Test
  void runsInsideAnApplicationTransactionAndCommitsIndependently() {
    List<String> log = new CopyOnWriteArrayList<>();
    runner("saga-in-transaction")
        .run(
            ctx -> {
              DataSource dataSource = ctx.getBean(DataSource.class);
              var transactions =
                  new TransactionTemplate(new DataSourceTransactionManager(dataSource));
              SagaDefinition<String> transfer =
                  new SagaDefinition<>(
                      "transfer", List.of(new Step("debit", log, Outcome.APPLIED)));
              SagaStateStore store = ctx.getBean(SagaStateStore.class);
              transactions.executeWithoutResult(
                  status -> {
                    assertTrue(store.find("s-tx").isEmpty());
                    SagaResult<String> result =
                        ctx.getBean(SagaOrchestrator.class).start(transfer, CODEC, "s-tx", "start");
                    assertTrue(result.completed());
                    status.setRollbackOnly();
                  });
              assertEquals(SagaStatus.COMPLETED, store.find("s-tx").orElseThrow().status());
              assertEquals(List.of("do:debit"), log);
            });
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
