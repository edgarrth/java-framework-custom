package pe.axiz.core.messaging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CloudEvents type handled by a {@link TypedEventHandler}, e.g. {@code
 * pe.axiz.payments.payment-created.v1}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface HandlesEvent {

  String value();
}
