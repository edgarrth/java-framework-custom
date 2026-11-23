package pe.axiz.core.http;

/** Suministra tokens de acceso para las llamadas salientes. */
@FunctionalInterface
public interface AccessTokenProvider {

  AccessToken token();
}
