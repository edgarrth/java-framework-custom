package pe.axiz.spring.foundation.aws;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/** Selects exactly one capability or service; unrelated Spring qualifiers have no Axiz scope. */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Qualifier public @interface AwsIdentityFor {
  AwsService service() default AwsService.UNSPECIFIED;

  AwsCapability capability() default AwsCapability.UNSPECIFIED;
}
