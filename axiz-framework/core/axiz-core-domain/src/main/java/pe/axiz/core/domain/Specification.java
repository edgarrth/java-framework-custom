package pe.axiz.core.domain;

import java.util.Objects;

/**
 * Regla de negocio sobre un candidato, componible con {@code and}, {@code or} y {@code not}.
 *
 * @param <T> tipo del candidato
 */
@FunctionalInterface
public interface Specification<T> {

  boolean isSatisfiedBy(T candidate);

  default Specification<T> and(Specification<T> other) {
    Objects.requireNonNull(other);
    return c -> isSatisfiedBy(c) && other.isSatisfiedBy(c);
  }

  default Specification<T> or(Specification<T> other) {
    Objects.requireNonNull(other);
    return c -> isSatisfiedBy(c) || other.isSatisfiedBy(c);
  }

  default Specification<T> not() {
    return c -> !isSatisfiedBy(c);
  }
}
