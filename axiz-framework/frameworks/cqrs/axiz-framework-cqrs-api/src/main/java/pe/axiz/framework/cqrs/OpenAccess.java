package pe.axiz.framework.cqrs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit, reviewed decision that this request needs no authority and no {@link AccessPolicy}: for
 * example a command only sent by an event consumer and never exposed over HTTP, or a public catalog
 * query. Without it (or one of those), the request is reported at startup as unprotected, and the
 * application fails to start when {@code axiz.cqrs.authorization.unprotected=fail}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OpenAccess {

  /** Why no check is needed (reviewed like any security decision). */
  String reason();
}
