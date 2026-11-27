package pe.axiz.spring.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reglas de paginación de un parámetro {@code PageRequest} de un controlador. El ordenamiento se
 * restringe a los campos listados; sin esta anotación, solo se aceptan identificadores simples como
 * campos de ordenamiento.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Paging {

  /** Campos por los que el cliente puede ordenar. */
  String[] sortable() default {};

  /** Ordenamiento por defecto, p. ej. {@code "createdAt,desc"}. */
  String[] defaultSort() default {};

  /**
   * Tamaño de página cuando el cliente no envía ninguno; 0 usa {@code
   * axiz.web.pagination.default-size}.
   */
  int defaultSize() default 0;
}
