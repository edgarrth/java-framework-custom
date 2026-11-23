package pe.axiz.core.saga;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * Respuesta definitiva de un participante: el paso se rechazó y su efecto no existe (por ejemplo,
 * fondos insuficientes). La saga compensa los pasos anteriores sin volver a consultar al
 * participante. Se lanza solo cuando el rechazo es seguro; cualquier otro fallo se trata como un
 * resultado incierto.
 */
public class SagaStepRejectedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public SagaStepRejectedException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
