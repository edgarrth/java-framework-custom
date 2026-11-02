package pe.axiz.test.contracts;

import java.util.Objects;

/**
 * One named property a store must satisfy.
 *
 * @param name readable name, used as the test display name
 * @param body the check; throws {@link AssertionError} when the property does not hold
 */
public record ContractCheck(String name, Body body) {

  public ContractCheck {
    Objects.requireNonNull(name);
    Objects.requireNonNull(body);
  }

  /** Check body. */
  @FunctionalInterface
  public interface Body {
    void run() throws Exception;
  }

  public void run() throws Exception {
    body.run();
  }
}
