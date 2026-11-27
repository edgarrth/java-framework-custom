package pe.axiz.core.errors;

import java.util.Optional;

/**
 * Traduce excepciones de terceros (persistencia, serialización, etc.) a excepciones de axiz. Los
 * mappers registrados se consultan en orden para cada excepción de la cadena de causas.
 */
@FunctionalInterface
public interface ExceptionMapper {

  Optional<AxizException> map(Throwable throwable);

  /**
   * Mapper para un tipo de excepción, por nombre de clase para no requerir las librerías
   * opcionales.
   *
   * @param className nombre de clase de la excepción completamente calificado
   * @param definition definición a usar
   * @return el mapper
   */
  static ExceptionMapper byClassName(String className, ErrorDefinition definition) {
    return t -> {
      for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
        if (c.getName().equals(className)) {
          return Optional.of(new AxizException(definition, t.getClass().getSimpleName(), t));
        }
      }
      return Optional.empty();
    };
  }
}
