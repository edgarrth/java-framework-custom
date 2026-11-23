package pe.axiz.extension.cqrs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Autoridades (cualquiera de ellas) que necesita quien llama para enviar un comando o una consulta.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiresAuthority {

  /** Nombres de autoridad, por ejemplo {@code SCOPE_cards:write} o {@code ROLE_TELLER}. */
  String[] value();
}
