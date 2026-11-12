package pe.axiz.adapter.observability.aws;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AwsClients;
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;

/** Composes AWS transport with Micrometer using the SDK's public plugin contract. */
public final class AwsObservability {
  private AwsObservability() {}

  private static <B extends AwsClientBuilder<B, C>, C> B instrument(
      B builder, ObservationRegistry registry) {
    return builder.addPlugin(
        configuration ->
            configuration.overrideConfiguration(
                overrides -> {
                  if (overrides.executionInterceptors().stream()
                      .noneMatch(ObservationExecutionInterceptor.class::isInstance))
                    overrides.addExecutionInterceptor(
                        new ObservationExecutionInterceptor(registry));
                }));
  }

  public static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder, ObservationRegistry registry, @Nullable String region, @Nullable String endpoint) {
    return build(builder, registry, region, endpoint, null);
  }

  public static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder,
      ObservationRegistry registry,
      @Nullable String region,
      @Nullable String endpoint,
      @Nullable Duration timeout) {
    return AwsClients.build(instrument(builder, registry), region, endpoint, timeout);
  }

  public static <B extends AwsClientBuilder<B, C>, C> C configured(
      B builder,
      ObservationRegistry registry,
      Function<String, @Nullable String> properties,
      String service) {
    return configured(builder, registry, properties, service, null);
  }

  public static <B extends AwsClientBuilder<B, C>, C> C configured(
      B builder,
      ObservationRegistry registry,
      Function<String, @Nullable String> properties,
      String service,
      @Nullable Duration timeout) {
    return AwsClients.configured(instrument(builder, registry), properties, service, timeout);
  }

  public static <B extends AwsClientBuilder<B, C> & AwsAsyncClientBuilder<B, C>, C> C configured(
      AsyncAwsClients owner,
      B builder,
      ObservationRegistry registry,
      Function<String, @Nullable String> properties,
      String service) {
    return configured(owner, builder, registry, properties, service, null);
  }

  public static <B extends AwsClientBuilder<B, C> & AwsAsyncClientBuilder<B, C>, C> C configured(
      AsyncAwsClients owner,
      B builder,
      ObservationRegistry registry,
      Function<String, @Nullable String> properties,
      String service,
      @Nullable Duration timeout) {
    return owner.configured(instrument(builder, registry), properties, service, timeout);
  }
}
