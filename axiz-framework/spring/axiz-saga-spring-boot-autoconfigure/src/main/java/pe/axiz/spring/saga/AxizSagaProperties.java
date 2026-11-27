package pe.axiz.spring.saga;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sagas orquestadas ({@code axiz.saga.*}) con estado persistido y compensación.
 *
 * @param stepAttempts intentos de un paso ante fallos transitorios
 * @param compensationAttempts intentos de una compensación
 * @param initialDelay retraso del primer reintento
 * @param maxDelay tope del retraso de reintento
 */
@ConfigurationProperties("axiz.saga")
public record AxizSagaProperties(
    @DefaultValue("3") int stepAttempts,
    @DefaultValue("5") int compensationAttempts,
    @DefaultValue("200ms") Duration initialDelay,
    @DefaultValue("5s") Duration maxDelay,
    @DefaultValue("30s") Duration executionLease) {}
