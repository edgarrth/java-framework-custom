package pe.axiz.adapter.aws.transport;

import java.net.URI;
import java.time.Duration;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.adapter.aws.identity.AwsCredentialsPlugin;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.AwsServiceClientConfiguration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.SdkPlugin;
import software.amazon.awssdk.core.SdkServiceClientConfiguration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;

/**
 * Applies the framework conventions to any AWS SDK v2 client builder: optional region and endpoint
 * override (LocalStack in local runs and tests). Credentials always follow {@link
 * AwsCredentialSources} when the builder uses SDK defaults. Explicit custom providers remain owned
 * by the caller according to the SDK lifecycle contract.
 */
public final class AwsClients {

  private AwsClients() {}

  public static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder, @Nullable String region, @Nullable String endpoint) {
    return build(builder, region, endpoint, null);
  }

  public static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder,
      @Nullable String region,
      @Nullable String endpoint,
      @Nullable Duration apiCallTimeout) {
    return build(builder, region, endpoint, apiCallTimeout, TransportSecurityPolicy.system());
  }

  private static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder,
      @Nullable String region,
      @Nullable String endpoint,
      @Nullable Duration apiCallTimeout,
      TransportSecurityPolicy transportSecurity) {
    try {
      if (apiCallTimeout != null && !apiCallTimeout.isPositive())
        throw new IllegalArgumentException("apiCallTimeout must be positive");
      if (apiCallTimeout != null) {
        var existing = builder.overrideConfiguration();
        var overrides =
            existing == null ? ClientOverrideConfiguration.builder() : existing.toBuilder();
        builder.overrideConfiguration(overrides.apiCallTimeout(apiCallTimeout).build());
      }
      if (region != null && !region.isBlank()) {
        builder.region(Region.of(region));
      }
      if (endpoint != null && !endpoint.isBlank()) {
        transportSecurity.validate(URI.create(endpoint), "AWS endpoint");
        builder.endpointOverride(URI.create(endpoint));
      }
      // A public SDK plugin sees the final provider, preserving explicit custom/static builders.
      AwsTransportSecurity.apply(builder, transportSecurity);
      var policy = new CredentialPolicy(transportSecurity);
      builder.addPlugin(policy);
      return builder.build();
    } catch (SdkClientException e) {
      closeCredentials(builder, e);
      if (region == null || region.isBlank()) {
        // the SDK chain found no region: say which setting the service is missing
        throw new IllegalStateException(
            "No AWS region for this client: set axiz.aws.region (or AWS_REGION), or turn off the"
                + " capability that creates it when the service does not use AWS",
            e);
      }
      throw e;
    } catch (RuntimeException | Error failure) {
      closeCredentials(builder, failure);
      throw failure;
    }
  }

  private static void closeCredentials(AwsClientBuilder<?, ?> builder, Throwable failure) {
    for (var plugin : builder.plugins()) {
      try {
        if (plugin instanceof AwsCredentialsPlugin credentials) credentials.close();
        else if (plugin instanceof CredentialPolicy policy) policy.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
    }
  }

  private static final class CredentialPolicy implements SdkPlugin {
    private @Nullable AwsCredentialSource source;
    private final TransportSecurityPolicy transportSecurity;

    private CredentialPolicy(TransportSecurityPolicy transportSecurity) {
      this.transportSecurity = transportSecurity;
    }

    @Override
    public void configureClient(SdkServiceClientConfiguration.Builder configuration) {
      if (configuration instanceof AwsServiceClientConfiguration.Builder aws
          && aws.credentialsProvider() instanceof DefaultCredentialsProvider
          && AwsCredentialSources.irsaDeclared()) {
        source = AwsCredentialSources.create(transportSecurity);
        new AwsCredentialsPlugin(source).configureClient(aws);
      }
    }

    @Override
    public void close() {
      if (source != null) source.close();
    }
  }

  /**
   * Same as {@link #build}, with region and endpoint taken from the framework properties: {@code
   * axiz.aws.region}, and {@code axiz.aws.<service>.endpoint} or else {@code axiz.aws.endpoint}.
   * Modules that create a client go through here, so a local run against LocalStack never reaches
   * real AWS because one module skipped the endpoint.
   *
   * @param properties property lookup, e.g. {@code environment::getProperty}
   * @param service endpoint key of the service: {@code sqs}, {@code dynamodb}, {@code kms}...
   */
  public static <B extends AwsClientBuilder<B, C>, C> C configured(
      B builder, Function<String, @Nullable String> properties, String service) {
    return configured(builder, properties, service, null);
  }

  public static <B extends AwsClientBuilder<B, C>, C> C configured(
      B builder,
      Function<String, @Nullable String> properties,
      String service,
      @Nullable Duration timeout) {
    try {
      var settings =
          AwsClientSettings.resolve(properties, service, AwsClientSettings.RegionPolicy.SDK_CHAIN);
      var region = settings.region();
      var endpoint = settings.endpoint();
      return build(
          builder,
          region == null ? null : region.id(),
          endpoint == null ? null : endpoint.toString(),
          timeout,
          settings.transportSecurity());
    } catch (RuntimeException | Error failure) {
      closeCredentials(builder, failure);
      throw failure;
    }
  }
}
