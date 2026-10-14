package pe.axiz.spring.inbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;

/**
 * Idempotent consumption ({@code axiz.inbox.*}): processed message ids per consumer.
 *
 * @param store {@code jdbc} (Aurora), {@code r2dbc} (Aurora from a reactive service) or {@code
 *     dynamodb}
 * @param table table name
 * @param lease how long a delivery owns a message before a redelivery may take over
 * @param retention how long processed ids are remembered (DynamoDB TTL)
 * @param purgeInterval how often expired JDBC or R2DBC rows are deleted
 * @param initializeSchema when the JDBC table is created by the starter
 * @param statementTimeout bound of each inbox statement over R2DBC
 */
@ConfigurationProperties("axiz.inbox")
public record AxizInboxProperties(
    @DefaultValue("jdbc") String store,
    @DefaultValue("axiz_inbox") String table,
    @DefaultValue("5m") Duration lease,
    @DefaultValue("14d") Duration retention,
    @DefaultValue("1h") Duration purgeInterval,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema,
    @DefaultValue("10s") Duration statementTimeout) {}
