package pe.axiz.spring.foundation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registers imperative wiring for servlet and non-web applications, never for reactive web
 * applications.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(ImperativeApplicationCondition.class)
public @interface ConditionalOnImperativeApplication {}
