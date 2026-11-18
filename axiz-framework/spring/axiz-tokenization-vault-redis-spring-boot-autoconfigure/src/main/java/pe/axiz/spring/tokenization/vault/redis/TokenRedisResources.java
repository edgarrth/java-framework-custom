package pe.axiz.spring.tokenization.vault.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.spring.tokenization.vault.redis.AxizTokenizationRedisProperties.Redis;

/** Owns one connection used only by one budget instance; no raw connection bean is exported. */
final class TokenRedisResources implements AutoCloseable {
  private final TokenRedisCredentials credentials;
  private final ClientResources resources;
  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final AtomicBoolean closed = new AtomicBoolean();

  private TokenRedisResources(
      TokenRedisCredentials credentials,
      ClientResources resources,
      RedisClient client,
      StatefulRedisConnection<String, String> connection) {
    this.credentials = credentials;
    this.resources = resources;
    this.client = client;
    this.connection = connection;
  }

  static TokenRedisResources open(
      Redis settings, DataTransportSecurityPolicy security, SecretResolver secrets) {
    security.requireVerified(settings.tls(), true, "Token budget Redis");
    var credentials = credentials(settings, secrets);
    ClientResources resources = null;
    RedisClient client = null;
    try {
      resources =
          DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
      var uri =
          RedisURI.Builder.redis(settings.host(), settings.port())
              .withSsl(settings.tls())
              .withVerifyPeer(SslVerifyMode.FULL)
              .withTimeout(settings.timeout())
              .withAuthentication(credentials)
              .build();
      client = RedisClient.create(resources, uri);
      client.setOptions(
          ClientOptions.builder()
              .socketOptions(SocketOptions.builder().connectTimeout(settings.timeout()).build())
              .timeoutOptions(TimeoutOptions.enabled())
              .requestQueueSize(settings.queueCapacity())
              .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS)
              .build());
      var connection = client.connect(); // Bootstrap only, native SDK work after publication.
      connection.setTimeout(settings.timeout());
      if (!"PONG".equals(connection.sync().ping())) throw unavailable();
      return new TokenRedisResources(credentials, resources, client, connection);
    } catch (RuntimeException failure) {
      stop(client, resources, credentials);
      throw unavailable();
    }
  }

  StatefulRedisConnection<String, String> connection() {
    return connection;
  }

  private static TokenRedisCredentials credentials(Redis settings, SecretResolver secrets) {
    try {
      return new TokenRedisCredentials(
          settings.username(), secrets.require(settings.passwordSecret()));
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    if (!stop(client, resources, credentials)) throw unavailable();
  }

  private static boolean stop(
      @Nullable RedisClient client,
      @Nullable ClientResources resources,
      TokenRedisCredentials credentials) {
    boolean failed = false;
    try {
      if (client != null)
        client.shutdown(Duration.ZERO, Duration.ofSeconds(3)); // Includes the owned connection.
    } catch (RuntimeException failure) {
      failed = true;
    }
    try {
      if (resources != null) resources.shutdown(0, 3, TimeUnit.SECONDS).get(4, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failed = true;
    } catch (RuntimeException | ExecutionException | TimeoutException failure) {
      failed = true;
    } finally {
      credentials.close();
    }
    return !failed;
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Dedicated token budget Redis transport is unavailable");
  }
}
