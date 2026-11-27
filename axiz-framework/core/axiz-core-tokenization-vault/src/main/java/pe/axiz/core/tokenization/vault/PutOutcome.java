package pe.axiz.core.tokenization.vault;

import java.util.Objects;
import pe.axiz.core.tokenization.Token;

/** Solo los conflictos condicionales confirmados son reintentables como colisiones. */
public sealed interface PutOutcome {
  /** Las referencias del token y del índice hicieron commit de forma atómica. */
  record Created() implements PutOutcome {}

  /**
   * Ganador confirmado del índice único; quienes llaman deben releerlo con consistencia fuerte y
   * validarlo.
   */
  record FingerprintExists(Token token) implements PutOutcome {
    public FingerprintExists {
      Objects.requireNonNull(token);
    }
  }

  /** El token aleatorio ya está ocupado; no concede acceso a su asociación. */
  record TokenCollision() implements PutOutcome {}

  /** La versión durable de la clave cambió antes de la transacción. */
  record StaleKeyState() implements PutOutcome {}
}
