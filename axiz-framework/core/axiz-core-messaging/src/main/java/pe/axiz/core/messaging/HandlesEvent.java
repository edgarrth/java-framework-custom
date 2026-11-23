package pe.axiz.core.messaging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tipo CloudEvents que maneja un {@link TypedEventHandler}, por ejemplo {@code
 * pe.axiz.payments.payment-created.v1}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface HandlesEvent {

  String value();
}
