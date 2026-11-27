package pe.axiz.spring.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tareas programadas con una única ejecución por clúster ({@code axiz.scheduling.*}).
 *
 * @param store {@code jdbc} (Aurora, por defecto), {@code r2dbc} (Aurora desde un servicio
 *     reactivo) o {@code dynamodb}
 * @param defaultLockAtMostFor lease de seguridad cuando un nodo muere mientras ejecuta una tarea
 */
@ConfigurationProperties("axiz.scheduling")
public record AxizSchedulingProperties(
    @DefaultValue("jdbc") String store, @DefaultValue("10m") Duration defaultLockAtMostFor) {}
