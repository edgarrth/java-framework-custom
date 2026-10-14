package pe.axiz.core.observability.awssdk;

import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
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
    builder.overrideConfiguration(
        ClientOverrideConfiguration.builder()
            .addExecutionInterceptor(new ObservationExecutionInterceptor(registry))
            .build());
    if (region != null && !region.isBlank()) {
      builder.region(Region.of(region));
    }
    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    return builder.build();
  }
}
