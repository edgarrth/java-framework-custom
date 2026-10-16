package pe.axiz.quality.archunit.sample.beans;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Stands in for Spring Boot's {@code @AutoConfiguration}. */
@Retention(RetentionPolicy.RUNTIME)
@Configuration
public @interface AutoConfiguration {}
