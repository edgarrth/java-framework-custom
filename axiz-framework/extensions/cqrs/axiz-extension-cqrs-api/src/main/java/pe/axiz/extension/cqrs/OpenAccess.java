package pe.axiz.extension.cqrs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Decisión explícita y revisada de que esta petición no necesita ninguna autoridad ni {@link
 * AccessPolicy}: por ejemplo, un comando que solo envía un consumidor de eventos y que nunca se
 * expone por HTTP, o una consulta de catálogo público. Sin ella (o sin una de esas), la petición se
 * informa al arrancar como no protegida, y la aplicación no arranca cuando {@code
 * axiz.cqrs.authorization.unprotected=fail}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OpenAccess {

  /**
   * Por qué no se necesita ninguna verificación (se revisa como cualquier decisión de seguridad).
   */
  String reason();
}
