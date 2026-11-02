package pe.axiz.extension.cqrs;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Who sends a command or query, as seen by an {@link AccessPolicy}.
 *
 * @param subject end user ({@code sub}); null or equal to the client for machine tokens
 * @param clientId calling application
 * @param tenant tenant, if any
 * @param issuer token issuer, if any
 * @param authorities granted authorities ({@code SCOPE_...}, {@code ROLE_...})
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

  /** No authenticated caller. */
  public static Caller anonymous() {
    return new Caller(null, null, null, null, Set.of());
  }

  public boolean authenticated() {
    return subject != null || clientId != null;
  }

  /** An application acting on its own (no end user). */
  public boolean isMachine() {
    return subject == null || subject.equals(clientId);
  }

  /** The end user {@code id} is the caller. */
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
