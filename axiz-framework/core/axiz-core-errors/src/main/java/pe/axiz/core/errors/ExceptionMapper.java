package pe.axiz.core.errors;

import java.util.Optional;

/**
 * Translates third-party exceptions (persistence, serialization, ...) into axiz exceptions.
 * Registered mappers are consulted in order for every exception in the cause chain.
 */
@FunctionalInterface
public interface ExceptionMapper {

  Optional<AxizException> map(Throwable throwable);

  /**
   * Mapper for an exception type, by class name so optional libraries are not required.
   *
   * @param className fully qualified exception class name
   * @param definition definition to use
   * @return the mapper
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
