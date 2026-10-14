package pe.axiz.core.domain;

import java.util.Objects;

/**
 * Business rule over a candidate, composable with {@code and}, {@code or} and {@code not}.
 *
 * @param <T> candidate type
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
