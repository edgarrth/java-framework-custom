package pe.axiz.spring.foundation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Presence condition for non-boolean settings. It does not interpret the literal "false" or resolve
 * placeholders before configuration binding; the owning properties validate their value.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConfiguredPropertyCondition.class)
public @interface ConditionalOnConfiguredProperty {
  String value();
}
