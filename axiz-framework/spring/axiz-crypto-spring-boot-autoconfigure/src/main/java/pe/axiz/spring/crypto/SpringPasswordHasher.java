package pe.axiz.spring.crypto;

import java.nio.CharBuffer;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import pe.axiz.core.crypto.PasswordHasher;

/** Argon2id and BCrypt through Spring Security's encoders. */
public final class SpringPasswordHasher implements PasswordHasher {

  private final PasswordEncoder encoder;

  public SpringPasswordHasher(PasswordEncoder encoder) {
    this.encoder = Objects.requireNonNull(encoder);
  }

  @Override
  public String hash(char[] password) {
    return Objects.requireNonNull(encoder.encode(CharBuffer.wrap(password)));
  }

  @Override
  public boolean matches(char[] password, String encoded) {
    return encoder.matches(CharBuffer.wrap(password), encoded);
  }
}
