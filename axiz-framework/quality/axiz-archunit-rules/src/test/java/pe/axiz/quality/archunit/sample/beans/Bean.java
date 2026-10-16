package pe.axiz.quality.archunit.sample.beans;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Stands in for Spring's {@code @Bean}. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {
  String[] name() default {};

  String[] value() default {};
}
