package pe.axiz.payments.settlement.job;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del job de liquidación ({@code settlement.*}).
 *
 * @param inputKey clave de objeto explícita por cargar (reprocesamiento); cuando está vacía, el
 *     archivo del día hábil previo bajo {@code inputPrefix}
 * @param inputPrefix carpeta de los archivos de la red en el bucket
 * @param maxDuration límite superior de una ejecución (lease del lock del clúster)
 * @param runOnStartup carga el archivo al iniciar el proceso (el CronJob); false solo donde el
 *     archivo se carga bajo demanda con {@link SettlementRunner#load(String)}, como en la prueba de
 *     la aplicación
 */
@ConfigurationProperties("settlement")
public record SettlementProperties(
    @Nullable String inputKey,
    @DefaultValue("in/settlement/") String inputPrefix,
    @DefaultValue("2h") Duration maxDuration,
    @DefaultValue("true") boolean runOnStartup) {}
