package pe.axiz.core.commons;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registra la revisión de estado mutable compartido; no prueba inmutabilidad ni seguridad entre
 * hilos.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface ReviewedState {
  /** Motivo concreto: copia inmutable, estrategia de sincronización o caché concurrente acotada. */
  String reason();
}
