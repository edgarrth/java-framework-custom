package pe.axiz.spring.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.adapter.outbox.jdbc.Dialect;
import pe.axiz.adapter.outbox.jdbc.JdbcAuditChainState;
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore;
import pe.axiz.core.audit.AuditChainState;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEvent.Actor;
import pe.axiz.core.audit.AuditEvent.Outcome;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.InMemoryAuditChainState;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.spring.foundation.DispatchBoundary;
import pe.axiz.spring.foundation.jdbc.SpringConnectionProvider;
import pe.axiz.spring.foundation.jdbc.SpringTransactionRunner;

/** Real commits and rollbacks with one connection expose publication at the wrong boundary. */
@Timeout(20)
class AuditBoundaryRegressionTest {
  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class Config {
    @Bean
    Business business(JdbcTemplate jdbc) {
      return new Business(jdbc);
    }
  }

  static class Business {
    private final JdbcTemplate jdbc;

    Business(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Auditable(action = "business.success", resource = "test")
    @Transactional
    public String success() {
      jdbc.update("INSERT INTO effects VALUES ('success')");
      return "ok";
    }

    @Auditable(action = "business.failure", resource = "test")
    @Transactional
    public void fail() {
      jdbc.update("INSERT INTO effects VALUES ('failure')");
      throw new IllegalStateException("original business failure");
    }
  }

  private HikariDataSource pool() {
    var config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:h2:mem:audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    config.setMaximumPoolSize(1);
    config.setConnectionTimeout(250);
    return new HikariDataSource(config);
  }

  private ApplicationContextRunner runner(HikariDataSource source) {
    new ResourceDatabasePopulator(new ClassPathResource("axiz/sql/outbox-h2.sql")).execute(source);
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("CREATE TABLE effects (id VARCHAR(64) PRIMARY KEY)");
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .withUserConfiguration(Config.class)
        .withPropertyValues(
            "axiz.audit.mode=DURABLE",
            "axiz.outbox.relay.enabled=false",
            "axiz.audit.durable.outside-transaction=fail")
        .withBean(DataSource.class, () -> source)
        .withBean(JdbcTemplate.class, () -> jdbc)
        .withBean(
            AuditChainState.class,
            () ->
                new JdbcAuditChainState(
                    new SpringConnectionProvider(source),
                    source,
                    "axiz_audit_chain",
                    Dialect.H2,
                    Clock.systemUTC(),
                    Duration.ofSeconds(2)))
        .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source))
        .withBean(
            OutboxWriter.class,
            () ->
                new OutboxWriter(
                    new JdbcOutboxStore(
                        new SpringConnectionProvider(source),
                        new SpringTransactionRunner(new DataSourceTransactionManager(source)),
                        "axiz_outbox",
                        Dialect.H2),
                    Clock.systemUTC()));
  }

  @Test
  void successfulAnnotatedTransactionKeepsAuditInItsOwnCommit() {
    try (var source = pool()) {
      runner(source)
          .run(
              ctx -> {
                assertEquals("ok", ctx.getBean(Business.class).success());
                var jdbc = ctx.getBean(JdbcTemplate.class);
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM effects", Integer.class));
                assertEquals(
                    1,
                    jdbc.queryForObject("SELECT COUNT(*) FROM axiz_outbox", Integer.class),
                    "a committed change must have durable evidence");
              });
    }
  }

  @Test
  void rolledBackBusinessFailureGetsDurableEvidenceAfterReleasingTheConnection() {
    try (var source = pool()) {
      runner(source)
          .run(
              ctx -> {
                var error =
                    assertThrows(
                        IllegalStateException.class, () -> ctx.getBean(Business.class).fail());
                assertEquals("original business failure", error.getMessage());
                var jdbc = ctx.getBean(JdbcTemplate.class);
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM effects", Integer.class));
                assertEquals(
                    1,
                    jdbc.queryForObject("SELECT COUNT(*) FROM axiz_outbox", Integer.class),
                    "failure audit survives rollback and uses the released connection");
              });
    }
  }

  @Test
  void messageBoundaryDrainsFailuresOnlyAfterTheOuterRollbackReleasesItsConnection() {
    try (var source = pool()) {
      runner(source)
          .run(
              ctx -> {
                var transaction =
                    new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
                try (var boundary = ctx.getBean(DispatchBoundary.class).open()) {
                  transaction.executeWithoutResult(
                      status -> {
                        assertThrows(
                            IllegalStateException.class, () -> ctx.getBean(Business.class).fail());
                        assertEquals(
                            0,
                            ctx.getBean(JdbcTemplate.class)
                                .queryForObject("SELECT COUNT(*) FROM axiz_outbox", Integer.class));
                        status.setRollbackOnly();
                      });
                }
                assertEquals(
                    0,
                    ctx.getBean(JdbcTemplate.class)
                        .queryForObject("SELECT COUNT(*) FROM effects", Integer.class));
                assertEquals(
                    1,
                    ctx.getBean(JdbcTemplate.class)
                        .queryForObject("SELECT COUNT(*) FROM axiz_outbox", Integer.class));
                var publisher = (TransactionalAuditPublisher) ctx.getBean(AuditPublisher.class);
                assertEquals(0, publisher.notConfirmedCount());
                String envelope =
                    ctx.getBean(JdbcTemplate.class)
                        .queryForObject("SELECT envelope FROM axiz_outbox", String.class);
                assertNotNull(envelope);
                assertEquals(
                    TransactionOutcome.ROLLED_BACK,
                    AuditEventJson.read(
                            new String(
                                CloudEventJson.read(envelope).data(), StandardCharsets.UTF_8))
                        .transactionOutcome());
              });
    }
  }

  @Test
  void saturatedPoolDegradesWithinItsOwnDeadlineWithoutMaskingTheFailure() throws Exception {
    try (var source = pool()) {
      runner(source)
          .run(
              ctx -> {
                var fallback = new CopyOnWriteArrayList<AuditEvent>();
                try (var publisher =
                    TransactionalAuditPublisher.builder()
                        .outbox(ctx.getBean(OutboxWriter.class))
                        .destination("audit")
                        .fallback(fallback::add)
                        .outside(AuditOutsideTransaction.FAIL)
                        .transactions(ctx.getBean(PlatformTransactionManager.class))
                        .timeout(Duration.ofMillis(30))
                        .concurrency(1)
                        .durable(
                            new AuditSealer(
                                "saturated",
                                AuditSealer.Mode.HASH,
                                ctx.getBean(AuditChainState.class),
                                null,
                                null))
                        .degradation(
                            new AuditSealer(
                                "fallback",
                                AuditSealer.Mode.HASH,
                                new InMemoryAuditChainState(Clock.systemUTC(), 8),
                                null,
                                null))
                        .build()) {
                  var event =
                      AuditEvent.builder()
                          .id("saturated")
                          .occurredAt(Instant.EPOCH)
                          .service("service")
                          .action("failure")
                          .resourceType("test")
                          .resourceId(null)
                          .actor(new Actor(null, null, null))
                          .outcome(Outcome.FAILURE)
                          .errorCode("original")
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
                  try (var held = source.getConnection()) {
                    assertTimeoutPreemptively(
                        Duration.ofSeconds(2), () -> publisher.publish(event));
                  }
                  assertEquals(1, publisher.notConfirmedCount());
                  assertEquals(1, fallback.size());
                  assertEquals("NOT_CONFIRMED", fallback.getFirst().persistence());
                  assertEquals("original", fallback.getFirst().errorCode());
                }
              });
    }
  }

  @Test
  void transactionEvidenceKeepsUncertainCommitSeparateFromConfirmedRollback() {
    var trace = new JdbcTransactionOutcome();
    trace.complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertEquals(
        TransactionOutcome.ROLLED_BACK,
        trace.local(new IllegalStateException("integration may have succeeded")));
    assertEquals(
        TransactionOutcome.UNKNOWN, trace.local(new TransactionSystemException("commit unknown")));
    assertEquals(
        TransactionOutcome.UNKNOWN, trace.local(new SQLException("connection lost", "08006")));
    assertEquals(
        TransactionOutcome.ROLLED_BACK,
        new JdbcTransactionOutcome().local(new UnexpectedRollbackException("rollback only")));
  }
}
