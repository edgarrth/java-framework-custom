package pe.axiz.spring.httpclient;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import pe.axiz.core.http.ClientSettings;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

/** Owned bounded native transport; TLS, resolver and loops are prepared only at startup. */
final class NativeHttpResources implements AutoCloseable {
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);
  private final ConnectionProvider connections;
  private final LoopResources loops;
  private final ReactorClientHttpConnector connector;
  private final AtomicBoolean closed = new AtomicBoolean();

  NativeHttpResources(
      ClientSettings settings, URI endpoint, int maxConnections, @Nullable SslBundle bundle) {
    connections =
        ConnectionProvider.builder("axiz-http-" + settings.name())
            .maxConnections(maxConnections)
            .pendingAcquireMaxCount(Math.max(16, Math.multiplyExact(maxConnections, 2)))
            .pendingAcquireTimeout(settings.timeouts().connect())
            .maxIdleTime(Duration.ofSeconds(30))
            .maxLifeTime(Duration.ofMinutes(5))
            .evictInBackground(Duration.ofSeconds(30))
            .build();
    loops = LoopResources.create("axiz-http-" + settings.name(), 1, 2, true);
    try {
      var transport =
          HttpClient.create(connections)
              .runOn(loops)
              .disableRetry(true)
              .followRedirect(false)
              .option(ChannelOption.ALLOCATOR, new UnpooledByteBufAllocator(true))
              .option(
                  ChannelOption.CONNECT_TIMEOUT_MILLIS,
                  Math.toIntExact(settings.timeouts().connect().toMillis()))
              .resolver(spec -> spec.queryTimeout(settings.timeouts().connect()))
              .responseTimeout(settings.timeouts().read());
      if ("https".equalsIgnoreCase(endpoint.getScheme())) {
        var context = warmedTrust(bundle);
        transport = transport.secure(ssl -> ssl.sslContext(context));
      }
      transport.warmup().block(CLOSE_TIMEOUT);
      connector = new ReactorClientHttpConnector(transport);
    } catch (RuntimeException | Error failure) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  ReactorClientHttpConnector connector() {
    return connector;
  }

  private static SslContext warmedTrust(@Nullable SslBundle bundle) {
    try {
      var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init((KeyStore) null);
      return Http11SslContextSpec.forClient()
          .configure(
              builder -> {
                builder.sslProvider(SslProvider.JDK);
                if (bundle == null) builder.trustManager(factory);
                else {
                  var trust = bundle.getManagers().getTrustManagerFactory();
                  builder.trustManager(trust == null ? factory : trust);
                  var keys = bundle.getManagers().getKeyManagerFactory();
                  if (keys != null) builder.keyManager(keys);
                  var protocols = bundle.getOptions().getEnabledProtocols();
                  if (protocols != null) builder.protocols(protocols);
                  var ciphers = bundle.getOptions().getCiphers();
                  if (ciphers != null) builder.ciphers(Arrays.asList(ciphers));
                }
              })
          .sslContext();
    } catch (GeneralSecurityException | SSLException failure) {
      throw new IllegalStateException("Cannot initialize HTTP TLS trust material", failure);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      connections.disposeLater().block(CLOSE_TIMEOUT);
    } finally {
      loops.disposeLater(Duration.ZERO, CLOSE_TIMEOUT).block(CLOSE_TIMEOUT);
    }
  }
}
