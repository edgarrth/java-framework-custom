package pe.axiz.spring.idempotency;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import pe.axiz.core.idempotency.StoredResponse;

/**
 * Escribe una respuesta almacenada para un {@link IdempotentReplay} (pilas servlet y reactiva):
 * estado almacenado, cabeceras reproducidas y los bytes originales, más {@code Idempotent-Replayed:
 * true}.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdempotencyReplayHandler {

  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  @ExceptionHandler(IdempotentReplay.class)
  public ResponseEntity<byte[]> replay(IdempotentReplay replay) {
    StoredResponse stored = replay.response();
    HttpHeaders headers = new HttpHeaders();
    stored.headers().forEach(headers::set);
    headers.set(REPLAYED_HEADER, "true");
    return ResponseEntity.status(stored.status()).headers(headers).body(stored.body());
  }
}
