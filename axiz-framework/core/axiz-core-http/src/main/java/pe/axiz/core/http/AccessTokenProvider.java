package pe.axiz.core.http;

/** Supplies access tokens for outbound calls. */
@FunctionalInterface
public interface AccessTokenProvider {

  AccessToken token();
}
