package pe.axiz.core.commons;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca una clase escrita por un generador y no por una persona (los modelos que openapi-generator
 * produce a partir del contrato de un servicio, ver axiz-parent-service).
 *
 * <p>Se conserva en los archivos de clase para que las herramientas de cobertura distingan el
 * código generado del código del servicio: JaCoCo excluye de sus contadores toda clase anotada con
 * una anotación de retención de clase o de runtime cuyo nombre simple contenga {@code Generated}.
 * Así, los umbrales de cobertura miden el código que las pruebas del servicio deben ejercitar; los
 * modelos generados quedan cubiertos por el contrato (su formato) y por las pruebas propias del
 * generador. {@code jakarta.annotation.Generated}, que el generador también escribe, tiene
 * retención de código fuente y no llega a los archivos de clase.
 *
 * <p>Nunca se coloca en código escrito a mano: lo sacaría de los umbrales de cobertura.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Generated {

  /**
   * Generador que escribió la clase.
   *
   * @return su nombre, como {@code openapi-generator}
   */
  String value();
}
