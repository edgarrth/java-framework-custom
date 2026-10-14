package pe.axiz.core.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Audits a method: actor, action, resource, outcome and (sanitized) arguments and result. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {

  /** Action name, e.g. {@code order.create}. */
  String action();

  /** Resource type, e.g. {@code order}. */
  String resource();

  /** Name of the argument holding the resource id, if any. */
  String resourceIdArgument() default "";

  boolean captureArguments() default true;

  boolean captureResult() default false;

  AuditPolicy policy() default AuditPolicy.ALWAYS;
}
