package pe.axiz.core.http;

import java.util.concurrent.CompletionStage;

/**
 * Adquisición asíncrona nativa de tokens; la cancelación libera la E/S pendiente de este llamador.
 */
@FunctionalInterface
public interface AsyncAccessTokenProvider {
  CompletionStage<AccessToken> token();
}
