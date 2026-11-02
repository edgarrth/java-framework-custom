package pe.axiz.extension.cqrs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Authorities (any of them) the caller needs to send a command or query. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiresAuthority {

  /** Authority names, e.g. {@code SCOPE_cards:write} or {@code ROLE_TELLER}. */
  String[] value();
}
