package pe.axiz.quality.archunit.sample.beans;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Sustituye a {@code @AutoConfiguration} de Spring Boot. */
@Retention(RetentionPolicy.RUNTIME)
@Configuration
public @interface AutoConfiguration {}
