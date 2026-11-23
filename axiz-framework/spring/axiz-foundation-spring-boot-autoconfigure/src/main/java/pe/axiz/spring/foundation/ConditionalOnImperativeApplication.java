package pe.axiz.spring.foundation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registra el cableado imperativo para aplicaciones servlet y no web, nunca para aplicaciones web
 * reactivas.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(ImperativeApplicationCondition.class)
public @interface ConditionalOnImperativeApplication {}
