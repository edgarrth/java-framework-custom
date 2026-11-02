package pe.axiz.core.http;

import java.util.concurrent.CompletionStage;

/** Native asynchronous token acquisition; cancellation releases this caller's pending I/O. */
@FunctionalInterface
public interface AsyncAccessTokenProvider {
  CompletionStage<AccessToken> token();
}
