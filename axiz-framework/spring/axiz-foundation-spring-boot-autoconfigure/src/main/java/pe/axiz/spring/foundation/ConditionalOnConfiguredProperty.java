package pe.axiz.spring.foundation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Condición de presencia para configuraciones no booleanas. No interpreta el literal "false" ni
 * resuelve placeholders antes del binding de la configuración; las propiedades propietarias validan
 * su valor.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConfiguredPropertyCondition.class)
public @interface ConditionalOnConfiguredProperty {
  String value();
}
