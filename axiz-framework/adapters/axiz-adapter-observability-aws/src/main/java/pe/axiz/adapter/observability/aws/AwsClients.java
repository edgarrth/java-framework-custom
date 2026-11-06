package pe.axiz.adapter.observability.aws;

import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;

/**
 * Applies the framework conventions to any AWS SDK v2 client builder: observation interceptor,
 * optional region and endpoint override (LocalStack in local runs and tests). Credentials always
 * come from the SDK default chain (IRSA or EKS Pod Identity); no credential code is involved.
 */
public final class AwsClients {

  private AwsClients() {}

  public static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder, ObservationRegistry registry, @Nullable String region, @Nullable String endpoint) {
    return build(builder, registry, region, endpoint, null);
  }

  public static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder,
      ObservationRegistry registry,
      @Nullable String region,
      @Nullable String endpoint,
      @Nullable Duration apiCallTimeout) {
    if (apiCallTimeout != null && !apiCallTimeout.isPositive())
      throw new IllegalArgumentException("apiCallTimeout must be positive");
    builder.overrideConfiguration(
        ClientOverrideConfiguration.builder()
            .addExecutionInterceptor(new ObservationExecutionInterceptor(registry))
            .apiCallTimeout(apiCallTimeout)
            .build());
    if (region != null && !region.isBlank()) {
      builder.region(Region.of(region));
    }
    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    try {
      return builder.build();
    } catch (SdkClientException e) {
      if (region == null || region.isBlank()) {
        // the SDK chain found no region: say which setting the service is missing
        throw new IllegalStateException(
            "No AWS region for this client: set axiz.aws.region (or AWS_REGION), or turn off the"
                + " capability that creates it when the service does not use AWS",
            e);
      }
      throw e;
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
      B builder,
      ObservationRegistry registry,
      Function<String, @Nullable String> properties,
      String service) {
    String endpoint = properties.apply("axiz.aws." + service + ".endpoint");
    if (endpoint == null || endpoint.isBlank()) {
      endpoint = properties.apply("axiz.aws.endpoint");
    }
    return build(builder, registry, properties.apply("axiz.aws.region"), endpoint);
  }
}
