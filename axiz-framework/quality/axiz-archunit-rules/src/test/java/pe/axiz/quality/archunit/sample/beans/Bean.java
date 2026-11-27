package pe.axiz.quality.archunit.sample.beans;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Sustituye a {@code @Bean} de Spring. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {
  String[] name() default {};

  String[] value() default {};
}
