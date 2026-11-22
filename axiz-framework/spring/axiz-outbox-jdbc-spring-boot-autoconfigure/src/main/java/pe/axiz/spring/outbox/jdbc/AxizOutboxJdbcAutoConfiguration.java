package pe.axiz.spring.outbox.jdbc;

import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import pe.axiz.adapter.outbox.jdbc.Dialect;
import pe.axiz.adapter.outbox.jdbc.JdbcAuditChainState;
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore;
import pe.axiz.core.audit.AuditChainState;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriteTracing;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.spring.jdbc.SchemaInitializer;
import pe.axiz.spring.jdbc.SpringConnectionProvider;
import pe.axiz.spring.jdbc.SpringTransactionRunner;
import pe.axiz.spring.jdbc.SqlDialects;
import pe.axiz.spring.outbox.OutboxRelayTransactions;

/** JDBC persistence only; scheduling and relay budgets are owned by the neutral base. */
@AutoConfiguration(
    beforeName = {
      "pe.axiz.spring.outbox.AxizOutboxAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditAutoConfiguration"
    },
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.outbox.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "axiz.outbox.store", havingValue = "jdbc", matchIfMissing = true)
@EnableConfigurationProperties(AxizOutboxJdbcProperties.class)
public class AxizOutboxJdbcAutoConfiguration {
  @Bean
  SchemaInitializer axizOutboxSchema(DataSource dataSource, AxizOutboxJdbcProperties p) {
    return new SchemaInitializer(
        dataSource,
        "outbox",
        p.initializeSchema(),
        Map.of("axiz_outbox", p.table(), "axiz_audit_chain", p.auditChainTable()));
  }

  @Bean
  @ConditionalOnMissingBean
  OutboxStore axizOutboxStore(
      DataSource dataSource,
      PlatformTransactionManager tx,
      AxizOutboxJdbcProperties p,
      SchemaInitializer axizOutboxSchema) {
    Dialect dialect = SqlDialects.isH2(dataSource) ? Dialect.H2 : Dialect.POSTGRESQL;
    OutboxStore store =
        new JdbcOutboxStore(
            new SpringConnectionProvider(dataSource),
            new SpringTransactionRunner(tx),
            p.table(),
            dialect);
    return store;
  }

  @Bean
  @ConditionalOnMissingBean(AuditChainState.class)
  AuditChainState axizJdbcAuditChainState(
      DataSource dataSource,
      AxizOutboxJdbcProperties p,
      ObjectProvider<Clock> clocks,
      SchemaInitializer axizOutboxSchema) {
    return new JdbcAuditChainState(
        new SpringConnectionProvider(dataSource),
        dataSource,
        p.auditChainTable(),
        SqlDialects.isH2(dataSource) ? Dialect.H2 : Dialect.POSTGRESQL,
        clocks.getIfAvailable(Clock::systemUTC),
        p.auditStatementTimeout());
  }

  @Bean
  @ConditionalOnMissingBean
  OutboxWriter axizOutboxWriter(
      OutboxStore store,
      ObjectProvider<Clock> clock,
      OutboxWriteTracing tracing,
      @Qualifier("axizOutboxPayloadProtector") PayloadProtector protection) {
    // every write keeps the trace of the caller: the relay continues it when it publishes
    return new OutboxWriter(store, clock.getIfAvailable(Clock::systemUTC), tracing, protection);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(PlatformTransactionManager.class)
  OutboxRelayTransactions axizOutboxRelayTransactions(PlatformTransactionManager tx) {
    return new OutboxRelayTransactions(new SpringTransactionRunner(tx));
  }
}
