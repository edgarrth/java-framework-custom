package pe.axiz.spring.tokenization.vault.redis;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider.ImmediateRedisCredentialsProvider;
import pe.axiz.core.secrets.SecretValue;

/**
 * Credenciales precargadas: la reconexión nunca invoca un resolvedor de secretos bloqueante en un
 * hilo de Netty.
 */
final class TokenRedisCredentials
    implements ImmediateRedisCredentialsProvider, RedisCredentials, AutoCloseable {
  private final String username;
  private final SecretValue password;

  TokenRedisCredentials(String username, SecretValue password) {
    this.username = username;
    this.password = password;
    if (!password.use(chars -> chars.length >= 1 && chars.length <= 4096)) {
      password.close();
      throw new IllegalArgumentException("Invalid token budget credential length");
    }
  }

  @Override
  public RedisCredentials resolveCredentialsNow() {
    return this;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public boolean hasUsername() {
    return true;
  }

  @Override
  public char[] getPassword() {
    return password.revealChars();
  }

  @Override
  public boolean hasPassword() {
    return true;
  }

  @Override
  public void close() {
    password.close();
  }

  @Override
  public String toString() {
    return "TokenRedisCredentials[****]";
  }
}
