package pe.axiz.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara un acceso que requiere autenticación no anónima, sin exigir una autoridad. El framework
 * instala un interceptor de autorización previo para este contrato.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AuthenticatedAccess {}
