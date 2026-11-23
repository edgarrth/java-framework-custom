package pe.axiz.test.contracts;

import java.util.Objects;

/**
 * Una propiedad con nombre que un almacén debe cumplir.
 *
 * @param name nombre legible, que se usa como nombre visible de la prueba
 * @param body la verificación; lanza {@link AssertionError} cuando la propiedad no se cumple
 */
public record ContractCheck(String name, Body body) {

  public ContractCheck {
    Objects.requireNonNull(name);
    Objects.requireNonNull(body);
  }

  /** Cuerpo de la verificación. */
  @FunctionalInterface
  public interface Body {
    void run() throws Exception;
  }

  public void run() throws Exception {
    body.run();
  }
}
