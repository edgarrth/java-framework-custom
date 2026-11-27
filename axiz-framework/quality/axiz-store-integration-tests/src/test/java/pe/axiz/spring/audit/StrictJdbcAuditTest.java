package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.spring.outbox.AxizOutboxAutoConfiguration;
import pe.axiz.spring.outbox.jdbc.AxizOutboxJdbcAutoConfiguration;

class StrictJdbcAuditTest {
  private JdbcDataSource source() {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:strict-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    return source;
  }

  private ApplicationContextRunner runner(
      JdbcDataSource source, PlatformTransactionManager transactions) {
    return new ApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditAutoConfiguration.class, AxizStrictAuditAutoConfiguration.class,
                AxizOutboxAutoConfiguration.class, AxizOutboxJdbcAutoConfiguration.class))
        .withPropertyValues(
            "axiz.audit.mode=ASYNC",
            "axiz.audit.strict.enabled=true",
            "axiz.audit.strict.mode=DURABLE",
            "axiz.outbox.relay.enabled=false")
        .withBean(DataSource.class, () -> source)
        .withBean(PlatformTransactionManager.class, () -> transactions);
  }

  private AuditEvent event() {
    return AuditEvent.builder()
        .id("confirmation")
        .occurredAt(Instant.EPOCH)
        .service("vault")
        .action("lookup")
        .resourceType("token")
        .resourceId(null)
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId(null)
        .channel(null)
        .tenant(null)
        .details(Map.of())
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  void confirmationCommitsIndependentlyAndCannotBeRevertedByTheCallersRollback() {
    var source = source();
    var transactions = new DataSourceTransactionManager(source);
    runner(source, transactions)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              var jdbc = new JdbcTemplate(source);
              jdbc.execute("CREATE TABLE business (id INT PRIMARY KEY)");
              new TransactionTemplate(transactions)
                  .executeWithoutResult(
                      status -> {
                        jdbc.update("INSERT INTO business VALUES (1)");
                        ctx.getBean(StrictAuditPublisher.class).publish(event());
                        assertEquals(
                            1,
                            jdbc.queryForObject("SELECT COUNT(*) FROM axiz_outbox", Integer.class));
                        status.setRollbackOnly();
                      });
              assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM business", Integer.class));
              var wire = jdbc.queryForObject("SELECT envelope FROM axiz_outbox", String.class);
              var stored =
                  AuditEventJson.read(
                      new String(CloudEventJson.read(wire).data(), StandardCharsets.UTF_8));
              assertEquals(
                  AuditChainVerifier.IntegrityStatus.INTACT,
                  AuditChainVerifier.verify(List.of(stored), null, null).integrity());
              assertEquals(
                  1L,
                  jdbc.queryForObject("SELECT last_sequence FROM axiz_audit_chain", Long.class));
            });
  }

  @Test
  void failedOutboxWritePropagatesAndRollsBackTheChain() {
    var source = source();
    runner(source, new DataSourceTransactionManager(source))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              var jdbc = new JdbcTemplate(source);
              jdbc.execute("ALTER TABLE axiz_outbox ADD CONSTRAINT reject_record CHECK (1=0)");
              assertThrows(
                  AuditPublishException.class,
                  () -> ctx.getBean(StrictAuditPublisher.class).publish(event()));
              assertEquals(
                  0, jdbc.queryForObject("SELECT COUNT(*) FROM axiz_outbox", Integer.class));
              assertEquals(
                  0, jdbc.queryForObject("SELECT COUNT(*) FROM axiz_audit_chain", Integer.class));
            });
  }

  @Test
  void aManagerForAnotherDatabaseIsRejectedDuringSchemaVerification() {
    var source = source();
    runner(source, new DataSourceTransactionManager(source()))
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .hasRootCauseMessage("Outbox requires a transaction-bound connection");
              assertEquals(
                  0,
                  new JdbcTemplate(source)
                      .queryForObject("SELECT COUNT(*) FROM axiz_outbox", Integer.class));
            });
  }
}
