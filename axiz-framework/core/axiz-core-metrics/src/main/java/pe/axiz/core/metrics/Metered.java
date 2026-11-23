package pe.axiz.core.metrics;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cuenta y mide un método como evento. El resultado y el motivo se derivan de la excepción; los
 * argumentos y el resultado que implementan {@link MetricSource} aportan monto, valor y tags.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Metered {

  /** Nombre del evento, por ejemplo {@code payment}. */
  String event();

  /** Categoría opcional; prevalece la definición del catálogo cuando existe. */
  String category() default "";

  /** Indica si se registra el timer de duración además del contador. */
  boolean duration() default true;
}
