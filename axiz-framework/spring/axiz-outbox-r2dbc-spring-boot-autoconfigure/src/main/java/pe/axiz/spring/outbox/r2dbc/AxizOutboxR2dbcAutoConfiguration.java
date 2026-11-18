package pe.axiz.spring.outbox.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.adapter.outbox.r2dbc.R2dbcAuditChainState;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxWriter;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.R2dbcTransactionRunner;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.reactor.audit.ReactiveAuditChainState;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;
import pe.axiz.spring.outbox.OutboxRelayTransactions;

/** R2DBC persistence only; scheduling and relay budgets are owned by the neutral base. */
@AutoConfiguration(
    beforeName = {
      "pe.axiz.spring.outbox.AxizOutboxAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditAutoConfiguration"
    },
    afterName = {
      "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
      "pe.axiz.spring.r2dbc.AxizR2dbcAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.outbox.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "axiz.outbox.store", havingValue = "r2dbc", matchIfMissing = false)
@EnableConfigurationProperties(AxizOutboxR2dbcProperties.class)
public class AxizOutboxR2dbcAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(OutboxStore.class)
  R2dbcOutboxStore axizOutboxStore(
      ConnectionFactory connectionFactory, AxizOutboxR2dbcProperties p) {
    return new R2dbcOutboxStore(new R2dbcSql(connectionFactory), p.table(), p.statementTimeout());
  }

  /** Writer used by business code: joins the caller's reactive transaction. */
  @Bean
  @ConditionalOnMissingBean
  ReactiveOutboxWriter axizReactiveOutboxWriter(
      ReactiveSql sql,
      AxizOutboxR2dbcProperties p,
      ObjectProvider<Clock> clock,
      @Qualifier("axizOutboxAsyncPayloadProtector") AsyncPayloadProtector protection) {
    return new R2dbcOutboxWriter(
        new R2dbcOutboxStore(sql, p.table(), p.statementTimeout()),
        clock.getIfAvailable(Clock::systemUTC),
        protection);
  }

  @Bean
  @ConditionalOnMissingBean(ReactiveAuditChainState.class)
  ReactiveAuditChainState axizR2dbcAuditChainState(
      ReactiveSql sql,
      ConnectionFactory connections,
      AxizOutboxR2dbcProperties p,
      ObjectProvider<Clock> clocks) {
    return new R2dbcAuditChainState(
        sql,
        connections,
        p.auditChainTable(),
        clocks.getIfAvailable(Clock::systemUTC),
        p.auditStatementTimeout());
  }

  @Bean
  @ConditionalOnMissingBean
  OutboxRelayTransactions axizOutboxRelayTransactions(
      ConnectionFactory connectionFactory, AxizOutboxR2dbcProperties p) {
    return new OutboxRelayTransactions(
        new R2dbcTransactionRunner(connectionFactory, p.statementTimeout()));
  }
}
