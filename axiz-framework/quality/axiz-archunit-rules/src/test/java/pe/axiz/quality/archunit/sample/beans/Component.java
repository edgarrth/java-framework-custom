package pe.axiz.quality.archunit.sample.beans;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Stands in for Spring's {@code @Component}: the rule takes annotation names. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Component {
  String value() default "";
}
