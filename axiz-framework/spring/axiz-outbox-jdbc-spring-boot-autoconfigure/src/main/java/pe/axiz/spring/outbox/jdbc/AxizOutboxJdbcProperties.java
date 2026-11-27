package pe.axiz.spring.outbox.jdbc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.jdbc.SchemaInitializer;

/** Configuración del esquema y de las sentencias JDBC. */
@ConfigurationProperties("axiz.outbox")
public record AxizOutboxJdbcProperties(
    @DefaultValue("axiz_outbox") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema,
    @DefaultValue("axiz_audit_chain") String auditChainTable,
    @DefaultValue("2s") Duration auditStatementTimeout) {}
