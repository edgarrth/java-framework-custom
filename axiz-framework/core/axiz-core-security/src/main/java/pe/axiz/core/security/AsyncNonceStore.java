package pe.axiz.core.security;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Protección atómica y asíncrona contra replay. Los errores del almacén deben hacer fallar la
 * autenticación en cerrado.
 */
@FunctionalInterface
public interface AsyncNonceStore {
  CompletionStage<Boolean> markUsed(String nonce, Duration ttl);
}
