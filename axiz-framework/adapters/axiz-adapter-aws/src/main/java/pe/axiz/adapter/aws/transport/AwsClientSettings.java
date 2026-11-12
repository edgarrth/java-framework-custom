package pe.axiz.adapter.aws.transport;

import java.net.URI;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.regions.Region;

/** One effective endpoint/region policy for SDK clients and S3 presigners; no Spring dependency. */
public record AwsClientSettings(@Nullable Region region, @Nullable URI endpoint) {
  /** Selects SDK defaults for imperative clients or a required explicit native region. */
  public enum RegionPolicy {
    SDK_CHAIN,
    EXPLICIT
  }

  /** SDK_CHAIN leaves an absent region to the builder; EXPLICIT never consults profiles or IMDS. */
  public static AwsClientSettings resolve(
      Function<String, @Nullable String> properties, String service, RegionPolicy policy) {
    Objects.requireNonNull(properties);
    Objects.requireNonNull(service);
    Objects.requireNonNull(policy);
    String region = nonBlank(properties.apply("axiz.aws.region"));
    if (policy == RegionPolicy.EXPLICIT) {
      if (region == null) region = nonBlank(System.getenv("AWS_REGION"));
      if (region == null)
        throw new IllegalStateException(
            "Async AWS clients require explicit axiz.aws.region or AWS_REGION");
    }
    String endpoint = nonBlank(properties.apply("axiz.aws." + service + ".endpoint"));
    if (endpoint == null) endpoint = nonBlank(properties.apply("axiz.aws.endpoint"));
    return new AwsClientSettings(
        region == null ? null : Region.of(region), endpoint == null ? null : URI.create(endpoint));
  }

  public boolean endpointOverridden() {
    return endpoint != null;
  }

  private static @Nullable String nonBlank(@Nullable String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
