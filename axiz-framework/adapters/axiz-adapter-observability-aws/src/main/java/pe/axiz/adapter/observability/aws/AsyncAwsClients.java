package pe.axiz.adapter.observability.aws;

import io.micrometer.observation.ObservationRegistry;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.xml.stream.XMLInputFactory;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.TlsTrustManagersProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Owned non-blocking transport and completion resources; construct during startup and close after
 * clients.
 */
public final class AsyncAwsClients implements SdkAutoCloseable {
  private final WarmedAwsCredentials credentials;
  private final BoundedCompletionExecutor completions;
  private final Runnable removeMeters;

  public AsyncAwsClients() {
    this(
        DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build(),
        Clock.systemUTC(),
        Duration.ofSeconds(15));
  }

  /** Owns the provided credential source, warming it before any native asynchronous call. */
  public AsyncAwsClients(AwsCredentialsProvider source, Clock clock, Duration refreshInterval) {
    this(source, clock, refreshInterval, 2, 256, null);
  }

  /** Configured framework-owned pool; name is a stable capability/bean name for metrics. */
  public AsyncAwsClients(String poolName, int workers, int queueCapacity) {
    this(
        DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build(),
        Clock.systemUTC(),
        Duration.ofSeconds(15),
        workers,
        queueCapacity,
        poolName);
  }

  /** Explicit bounds for applications with their own warmed credential source. */
  public AsyncAwsClients(
      AwsCredentialsProvider source,
      Clock clock,
      Duration refreshInterval,
      int workers,
      int queueCapacity) {
    this(source, clock, refreshInterval, workers, queueCapacity, "aws-completion");
  }

  private AsyncAwsClients(
      AwsCredentialsProvider source,
      Clock clock,
      Duration refreshInterval,
      int workers,
      int queueCapacity,
      @Nullable String poolName) {
    BoundedCompletionExecutor.validWorkers(workers, queueCapacity);
    if (poolName != null && poolName.isBlank())
      throw new IllegalArgumentException("poolName must not be blank");
    credentials = new WarmedAwsCredentials(source, clock, refreshInterval);
    try {
      completions = new BoundedCompletionExecutor(workers, queueCapacity);
    } catch (RuntimeException | Error failure) {
      credentials.close();
      throw failure;
    }
    try {
      removeMeters = completionMetrics(poolName, completions);
    } catch (RuntimeException | Error failure) {
      completions.shutdownNow();
      credentials.close();
      throw failure;
    }
  }

  private static Runnable completionMetrics(
      @Nullable String name, BoundedCompletionExecutor executor) {
    if (name == null) return () -> {};
    try {
      Class.forName(
          "io.micrometer.core.instrument.MeterRegistry",
          false,
          AsyncAwsClients.class.getClassLoader());
    } catch (ClassNotFoundException unavailable) {
      return () -> {};
    }
    return AwsCompletionMetrics.bind(name, executor);
  }

  /** Snapshot-only, non-owning credentials for auxiliary signers; this owner controls refresh. */
  @SuppressWarnings("UnnecessaryMethodReference") // The interface view must not expose close().
  public AwsCredentialsProvider credentialsProvider() {
    return credentials::resolveCredentials;
  }

  public <B extends AwsClientBuilder<B, C> & AwsAsyncClientBuilder<B, C>, C> C configured(
      B builder,
      ObservationRegistry observations,
      Function<String, @Nullable String> properties,
      String service) {
    return configured(builder, observations, properties, service, null);
  }

  public <B extends AwsClientBuilder<B, C> & AwsAsyncClientBuilder<B, C>, C> C configured(
      B builder,
      ObservationRegistry observations,
      Function<String, @Nullable String> properties,
      String service,
      @Nullable Duration apiCallTimeout) {
    if (apiCallTimeout != null && !apiCallTimeout.isPositive())
      throw new IllegalArgumentException("apiCallTimeout must be positive");
    // The SDK creates per-thread XML parsers on network workers. Discover the provider and
    // load the JDK XML policy files now, while configuring the client on the startup thread.
    XMLInputFactory.newFactory();
    String region = properties.apply("axiz.aws.region");
    if (region == null || region.isBlank()) region = System.getenv("AWS_REGION");
    if (region == null || region.isBlank())
      throw new IllegalStateException(
          "Async AWS clients require explicit axiz.aws.region or AWS_REGION");
    // SDK clients close identity providers; only this owner may close the shared refresher.
    builder.region(Region.of(region)).credentialsProvider(credentialsProvider());
    String endpoint = properties.apply("axiz.aws." + service + ".endpoint");
    if (endpoint == null || endpoint.isBlank()) endpoint = properties.apply("axiz.aws.endpoint");
    if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
    builder.httpClientBuilder(
        NettyNioAsyncHttpClient.builder()
            .maxConcurrency(64)
            // Adaptive pooling parks event loops while expanding magazines. Own independent
            // chunks instead; demand and transport concurrency bound live buffers.
            .putChannelOption(ChannelOption.ALLOCATOR, new UnpooledByteBufAllocator(true))
            .useNonBlockingDnsResolver(true)
            .tlsTrustManagersProvider(warmedTrustManagers())
            .connectionTimeout(Duration.ofSeconds(3))
            .connectionAcquisitionTimeout(Duration.ofSeconds(5))
            .tlsNegotiationTimeout(Duration.ofSeconds(5))
            .connectionTimeToLive(
                duration(properties, "axiz.aws.async.connection-ttl", Duration.ofMinutes(5)))
            .connectionMaxIdleTime(
                duration(properties, "axiz.aws.async.max-idle-time", Duration.ofSeconds(30)))
            .useIdleConnectionReaper(true)
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30)));
    builder.asyncConfiguration(
        config ->
            config.advancedOption(
                SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, completions));
    var overrides =
        ClientOverrideConfiguration.builder()
            .apiCallAttemptTimeout(Duration.ofSeconds(30))
            .apiCallTimeout(Duration.ofSeconds(60))
            .addExecutionInterceptor(new ObservationExecutionInterceptor(observations));
    if (apiCallTimeout != null)
      overrides
          .apiCallTimeout(apiCallTimeout)
          .apiCallAttemptTimeout(
              apiCallTimeout.compareTo(Duration.ofSeconds(30)) < 0
                  ? apiCallTimeout
                  : Duration.ofSeconds(30));
    if (Set.of("dynamodb", "kms", "secretsmanager", "ssm", "sqs", "sns").contains(service)) {
      // These services marshal finite in-memory payloads. The SDK's standard SigV4 signer uses
      // per-thread SHA-256 rather than the new signer's blocking shared digest pool (AWS #6590).
      // Keep streaming S3 on its native signer; never disable payload signing or use reflection.
      overrides.putAdvancedOption(SdkAdvancedClientOption.SIGNER, Aws4Signer.create());
    }
    builder.overrideConfiguration(overrides.build());
    return builder.build();
  }

  private static TlsTrustManagersProvider warmedTrustManagers() {
    try {
      var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init((KeyStore) null);
      TrustManager[] snapshot = factory.getTrustManagers();
      return () -> snapshot.clone();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(
          "Cannot initialize AWS TLS trust managers at startup", failure);
    }
  }

  static Duration duration(
      Function<String, @Nullable String> properties, String key, Duration fallback) {
    String raw = properties.apply(key);
    if (raw == null || raw.isBlank()) return fallback;
    try {
      Duration parsed;
      if (raw.startsWith("P")) parsed = Duration.parse(raw);
      else {
        var match = Pattern.compile("([0-9]+)(ms|s|m|h)").matcher(raw);
        if (!match.matches()) throw new IllegalArgumentException("Invalid duration");
        long value = Long.parseLong(match.group(1));
        parsed =
            switch (match.group(2)) {
              case "ms" -> Duration.ofMillis(value);
              case "s" -> Duration.ofSeconds(value);
              case "m" -> Duration.ofMinutes(value);
              default -> Duration.ofHours(value);
            };
      }
      if (!parsed.isPositive() || parsed.toMillis() < 1)
        throw new IllegalArgumentException("Duration must be positive");
      return parsed;
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Invalid positive duration for " + key);
    }
  }

  @Override
  public void close() {
    completions.shutdown();
    try {
      credentials.close();
    } finally {
      removeMeters.run();
    }
  }
}
