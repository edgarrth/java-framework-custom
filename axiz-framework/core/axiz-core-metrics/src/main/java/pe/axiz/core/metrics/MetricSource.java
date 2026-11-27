package pe.axiz.core.metrics;

/**
 * Lo implementan los resultados (o comandos) que aportan a la métrica registrada por {@link
 * Metered}: monto, valor y tags. Una interfaz simple en lugar de expresiones, de modo que funciona
 * igual en una imagen nativa.
 */
@FunctionalInterface
public interface MetricSource {

  void describe(MetricEvent event);
}
