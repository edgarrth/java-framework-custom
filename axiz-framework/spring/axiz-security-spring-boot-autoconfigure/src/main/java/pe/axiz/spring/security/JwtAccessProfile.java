package pe.axiz.spring.security;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import pe.axiz.core.json.JsonReader;

/**
 * Contratos disjuntos de token de acceso seleccionados por el emisor de confianza, nunca por un
 * token entrante.
 */
public enum JwtAccessProfile {
  /**
   * Tokens de acceso JWT sin typ o con typ JWT; el aislamiento por emisor y audiencia sigue siendo
   * obligatorio.
   */
  COMPATIBLE,
  /**
   * Tipo de token de acceso explícito y claims de identidad obligatorios de RFC 9068; RS256 está
   * prohibido.
   */
  STRICT;

  OAuth2TokenValidator<Jwt> validator() {
    return jwt -> {
      Object type = jwt.getHeaders().get("typ");
      boolean accepted =
          this == STRICT
              ? ("at+jwt".equals(type) || "application/at+jwt".equals(type))
                  && jwt.getIssuedAt() != null
                  && originalClaims(jwt.getTokenValue())
              : type == null || "JWT".equals(type);
      return accepted
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(
              new OAuth2Error(
                  "invalid_token",
                  "Token does not satisfy the configured access-token profile",
                  null));
    };
  }

  private static boolean originalClaims(String token) {
    // Se ejecuta después de verificar la firma. Nimbus convierte un sub numérico en texto y Spring
    // sintetiza un iat ausente a partir de exp. El contrato estricto también debe validar el JSON
    // firmado original.
    if (token.length() > 65_536) return false;
    int first = token.indexOf('.');
    int last = token.lastIndexOf('.');
    if (first < 1 || last <= first + 1) return false;
    try {
      byte[] payload = Base64.getUrlDecoder().decode(token.substring(first + 1, last));
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(payload))
              .toString();
      var claims = JsonReader.parseObject(json);
      if (!(claims.get("iat") instanceof Number)) return false;
      for (String name : List.of("sub", "client_id", "jti")) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 1024) return false;
      }
      return true;
    } catch (IllegalArgumentException | CharacterCodingException malformed) {
      return false;
    }
  }
}
