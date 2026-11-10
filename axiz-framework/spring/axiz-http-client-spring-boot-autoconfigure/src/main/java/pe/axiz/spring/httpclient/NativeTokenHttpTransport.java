package pe.axiz.spring.httpclient;

import io.micrometer.observation.ObservationRegistry;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.http.AsyncHttpExchange;
import pe.axiz.core.http.ClientSettings;
import reactor.adapter.JdkFlowAdapter;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

/** Startup-owned native DNS, HTTP and bounded pooling for the token endpoint. */
final class NativeTokenHttpTransport implements AsyncHttpExchange, AutoCloseable {
  private static final int MAX_BYTES = 65_536;
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);
  private final ConnectionProvider connections;
  private final LoopResources loops;
  private final WebClient client;
  private final Duration timeout;
  private final AtomicBoolean closed = new AtomicBoolean();

  NativeTokenHttpTransport(
      ClientSettings settings, ObservationRegistry observations, @Nullable SslBundle bundle) {
    this.timeout = settings.timeouts().read();
    connections =
        ConnectionProvider.builder("axiz-oauth-" + settings.name())
            .maxConnections(2)
            .pendingAcquireMaxCount(16)
            .pendingAcquireTimeout(Duration.ofSeconds(5))
            .maxIdleTime(Duration.ofSeconds(30))
            .maxLifeTime(Duration.ofMinutes(5))
            .evictInBackground(Duration.ofSeconds(30))
            .build();
    loops = LoopResources.create("axiz-oauth-http", 1, 2, true);
    try {
      var transport =
          HttpClient.create(connections)
              .runOn(loops)
              .followRedirect(false)
              .option(ChannelOption.ALLOCATOR, new UnpooledByteBufAllocator(true))
              .option(
                  ChannelOption.CONNECT_TIMEOUT_MILLIS,
                  Math.toIntExact(settings.timeouts().connect().toMillis()))
              .resolver(spec -> spec.queryTimeout(settings.timeouts().connect()))
              .responseTimeout(timeout);
      if ("https"
          .equalsIgnoreCase(Objects.requireNonNull(settings.auth().tokenUri()).getScheme())) {
        var context = warmedTrust(bundle);
        transport = transport.secure(ssl -> ssl.sslContext(context));
      }
      // Warm the owned resolver, loops and transport libraries on the startup boundary.
      transport.warmup().block(CLOSE_TIMEOUT);
      client =
          WebClient.builder()
              .clientConnector(new ReactorClientHttpConnector(transport))
              .observationRegistry(observations)
              .observationConvention(new ReactiveClientNameObservationConvention(settings.name()))
              .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BYTES))
              .build();
    } catch (RuntimeException | Error failure) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  @Override
  public CompletionStage<Response> exchange(HttpRequest request, long maxResponseBytes) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Token transport is closed"));
    if (maxResponseBytes < 1 || maxResponseBytes > MAX_BYTES)
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Token response limit exceeds transport capacity"));
    var outgoing =
        client
            .method(HttpMethod.valueOf(request.method()))
            .uri(request.uri())
            .headers(headers -> request.headers().map().forEach(headers::put));
    var payload =
        request
            .bodyPublisher()
            .map(
                body ->
                    JdkFlowAdapter.flowPublisherToFlux(body)
                        .<DataBuffer>map(DefaultDataBufferFactory.sharedInstance::wrap));
    request
        .bodyPublisher()
        .ifPresent(
            body -> {
              if (body.contentLength() >= 0) outgoing.contentLength(body.contentLength());
            });
    WebClient.RequestHeadersSpec<?> prepared =
        payload.isPresent()
            ? outgoing.body(BodyInserters.fromDataBuffers(payload.get()))
            : outgoing;
    return Stages.requireValue(
        prepared
            .exchangeToMono(
                response -> {
                  String type = response.headers().contentType().map(Object::toString).orElse(null);
                  int status = response.statusCode().value();
                  return DataBufferUtils.join(
                          response.body(BodyExtractors.toDataBuffers()),
                          Math.toIntExact(maxResponseBytes))
                      .map(
                          buffer -> {
                            try {
                              return new Response(
                                  status, type, buffer.toString(StandardCharsets.UTF_8));
                            } finally {
                              DataBufferUtils.release(buffer);
                            }
                          })
                      .defaultIfEmpty(new Response(status, type, ""));
                })
            .timeout(request.timeout().orElse(timeout))
            .contextCapture()
            .toFuture());
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
      throw new IllegalStateException("Cannot initialize OAuth TLS trust material", failure);
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
