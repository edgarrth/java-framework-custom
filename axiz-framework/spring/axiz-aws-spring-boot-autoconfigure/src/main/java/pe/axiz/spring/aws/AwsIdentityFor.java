package pe.axiz.spring.aws;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Selecciona exactamente una capacidad o un servicio; los calificadores de Spring no relacionados
 * no tienen ámbito en Axiz.
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Qualifier public @interface AwsIdentityFor {
  AwsService service() default AwsService.UNSPECIFIED;

  AwsCapability capability() default AwsCapability.UNSPECIFIED;
}
