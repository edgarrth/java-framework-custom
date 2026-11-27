package pe.axiz.quality.archunit.sample.beans;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Sustituye a {@code @Configuration} de Spring (meta-anotada, como la real). */
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface Configuration {
  String value() default "";
}
