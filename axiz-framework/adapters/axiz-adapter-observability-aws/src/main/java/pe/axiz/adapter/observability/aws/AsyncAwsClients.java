package pe.axiz.adapter.observability.aws;

import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Owned non-blocking transport and completion resources; construct during startup and close after
 * clients.
 */
public final class AsyncAwsClients implements SdkAutoCloseable {
  private final WarmedAwsCredentials credentials;
  private final ThreadPoolExecutor completions;

  public AsyncAwsClients() {
    this(
        DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build(),
        Clock.systemUTC(),
        Duration.ofSeconds(15));
  }

  /** Owns the provided credential source, warming it before any native asynchronous call. */
  public AsyncAwsClients(
      software.amazon.awssdk.auth.credentials.AwsCredentialsProvider source,
      Clock clock,
      Duration refreshInterval) {
    credentials = new WarmedAwsCredentials(source, clock, refreshInterval);
    completions = new BoundedCompletionExecutor();
  }

  /** Snapshot-only, non-owning credentials for auxiliary signers; this owner controls refresh. */
  @SuppressWarnings("UnnecessaryMethodReference") // The interface view must not expose close().
  public software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentialsProvider() {
    return credentials::resolveCredentials;
  }

  public <B extends AwsClientBuilder<B, C> & AwsAsyncClientBuilder<B, C>, C> C configured(
      B builder,
      ObservationRegistry observations,
      java.util.function.Function<String, @Nullable String> properties,
      String service) {
    // The SDK creates per-thread XML parsers on network workers. Discover the provider and
    // load the JDK XML policy files now, while configuring the client on the startup thread.
    javax.xml.stream.XMLInputFactory.newFactory();
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
            .putChannelOption(
                io.netty.channel.ChannelOption.ALLOCATOR,
                new io.netty.buffer.UnpooledByteBufAllocator(true))
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
    if (java.util.Set.of("dynamodb", "kms", "secretsmanager", "ssm", "sqs", "sns")
        .contains(service)) {
      // These services marshal finite in-memory payloads. The SDK's standard SigV4 signer uses
      // per-thread SHA-256 rather than the new signer's blocking shared digest pool (AWS #6590).
      // Keep streaming S3 on its native signer; never disable payload signing or use reflection.
      overrides.putAdvancedOption(
          software.amazon.awssdk.core.client.config.SdkAdvancedClientOption.SIGNER,
          software.amazon.awssdk.auth.signer.Aws4Signer.create());
    }
    builder.overrideConfiguration(overrides.build());
    return builder.build();
  }

  private static software.amazon.awssdk.http.TlsTrustManagersProvider warmedTrustManagers() {
    try {
      var factory =
          javax.net.ssl.TrustManagerFactory.getInstance(
              javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
      factory.init((java.security.KeyStore) null);
      javax.net.ssl.TrustManager[] snapshot = factory.getTrustManagers();
      return () -> snapshot.clone();
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException(
          "Cannot initialize AWS TLS trust managers at startup", failure);
    }
  }

  static Duration duration(
      java.util.function.Function<String, @Nullable String> properties,
      String key,
      Duration fallback) {
    String raw = properties.apply(key);
    if (raw == null || raw.isBlank()) return fallback;
    try {
      Duration parsed;
      if (raw.startsWith("P")) parsed = Duration.parse(raw);
      else {
        var match = java.util.regex.Pattern.compile("([0-9]+)(ms|s|m|h)").matcher(raw);
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
    credentials.close();
  }
}
