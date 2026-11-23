package pe.axiz.extension.cqrs;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Quién envía un comando o una consulta, tal como lo ve una {@link AccessPolicy}.
 *
 * @param subject usuario final ({@code sub}); null o igual al cliente para tokens de máquina
 * @param clientId aplicación que llama
 * @param tenant tenant, si existe
 * @param issuer emisor del token, si existe
 * @param authorities autoridades concedidas ({@code SCOPE_...}, {@code ROLE_...})
 */
public record Caller(
    @Nullable String subject,
    @Nullable String clientId,
    @Nullable String tenant,
    @Nullable String issuer,
    Set<String> authorities) {

  public Caller {
    authorities = Set.copyOf(Objects.requireNonNull(authorities));
  }

  /** Ningún llamador autenticado. */
  public static Caller anonymous() {
    return new Caller(null, null, null, null, Set.of());
  }

  public boolean authenticated() {
    return subject != null || clientId != null;
  }

  /** Aplicación que actúa por sí misma (sin usuario final). */
  public boolean isMachine() {
    return subject == null || subject.equals(clientId);
  }

  /** El usuario final {@code id} es quien llama. */
  public boolean isUser(@Nullable String id) {
    return id != null && !isMachine() && id.equals(subject);
  }

  public boolean hasAuthority(String authority) {
    return authorities.contains(authority);
  }

  public boolean hasAnyAuthority(String... candidates) {
    return Arrays.stream(candidates).anyMatch(authorities::contains);
  }
}
