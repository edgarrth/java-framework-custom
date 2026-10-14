package pe.axiz.spring.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Paging rules of a {@code PageRequest} controller parameter. Sorting is restricted to the listed
 * fields; without this annotation only plain identifiers are accepted as sort fields.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Paging {

  /** Fields the client may sort by. */
  String[] sortable() default {};

  /** Default sort, e.g. {@code "createdAt,desc"}. */
  String[] defaultSort() default {};

  /** Page size when the client sends none; 0 uses {@code axiz.web.pagination.default-size}. */
  int defaultSize() default 0;
}
