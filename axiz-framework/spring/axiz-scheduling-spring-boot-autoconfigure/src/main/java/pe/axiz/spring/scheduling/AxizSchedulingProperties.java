package pe.axiz.spring.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Scheduled tasks with a single execution per cluster ({@code axiz.scheduling.*}).
 *
 * @param store {@code jdbc} (Aurora, default), {@code r2dbc} (Aurora from a reactive service) or
 *     {@code dynamodb}
 * @param defaultLockAtMostFor safety lease when a node dies while running a task
 */
@ConfigurationProperties("axiz.scheduling")
public record AxizSchedulingProperties(
    @DefaultValue("jdbc") String store, @DefaultValue("10m") Duration defaultLockAtMostFor) {}
