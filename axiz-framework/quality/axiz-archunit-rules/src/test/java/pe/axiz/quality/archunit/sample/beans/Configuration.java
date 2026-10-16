package pe.axiz.quality.archunit.sample.beans;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Stands in for Spring's {@code @Configuration} (meta-annotated, like the real one). */
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface Configuration {
  String value() default "";
}
