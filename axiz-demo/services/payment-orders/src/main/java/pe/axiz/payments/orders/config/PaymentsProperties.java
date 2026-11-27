package pe.axiz.payments.orders.config;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de negocio de los pagos ({@code payments.*}, ConfigMap por entorno).
 *
 * @param cutoff las órdenes recibidas después de esta hora local se procesan el siguiente día hábil
 * @param dailyLimits límite diario por código de moneda
 */
@ConfigurationProperties("payments")
public record PaymentsProperties(
    @DefaultValue("18:00") LocalTime cutoff, @DefaultValue Map<String, BigDecimal> dailyLimits) {
  public PaymentsProperties {
    dailyLimits = Collections.unmodifiableMap(new LinkedHashMap<>(dailyLimits));
  }
}
