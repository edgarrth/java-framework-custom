package pe.axiz.spring.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;

/**
 * Scheduled tasks with a single execution per cluster ({@code axiz.scheduling.*}).
 *
 * @param store {@code jdbc} (Aurora, default), {@code r2dbc} (Aurora from a reactive service) or
 *     {@code dynamodb}
 * @param table lock table
 * @param defaultLockAtMostFor safety lease when a node dies while running a task
 * @param initializeSchema when the JDBC table is created by the starter
 */
@ConfigurationProperties("axiz.scheduling")
public record AxizSchedulingProperties(
    @DefaultValue("jdbc") String store,
    @DefaultValue("shedlock") String table,
    @DefaultValue("10m") Duration defaultLockAtMostFor,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema) {}
