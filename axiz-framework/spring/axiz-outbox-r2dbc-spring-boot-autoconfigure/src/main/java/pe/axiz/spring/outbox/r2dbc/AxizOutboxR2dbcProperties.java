package pe.axiz.spring.outbox.r2dbc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** R2DBC schema and statement settings. */
@ConfigurationProperties("axiz.outbox")
public record AxizOutboxR2dbcProperties(
    @DefaultValue("axiz_outbox") String table,
    @DefaultValue("30s") Duration statementTimeout,
    @DefaultValue("axiz_audit_chain") String auditChainTable,
    @DefaultValue("2s") Duration auditStatementTimeout) {
  public AxizOutboxR2dbcProperties {
    if (statementTimeout.isNegative() || statementTimeout.isZero())
      throw new IllegalArgumentException("Outbox statement-timeout must be positive");
  }
}
