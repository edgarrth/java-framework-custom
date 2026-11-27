package pe.axiz.adapter.aws.transport;

import java.net.URI;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.regions.Region;

/**
 * Política única de endpoint y región para clientes del SDK y presigners de S3; sin dependencia de
 * Spring.
 */
public record AwsClientSettings(
    @Nullable Region region, @Nullable URI endpoint, TransportSecurityPolicy transportSecurity) {
  /**
   * Selecciona los valores por defecto del SDK para clientes imperativos o una región nativa
   * explícita obligatoria.
   */
  public enum RegionPolicy {
    SDK_CHAIN,
    EXPLICIT
  }

  /** SDK_CHAIN deja al builder una región ausente; EXPLICIT nunca consulta perfiles ni IMDS. */
  public static AwsClientSettings resolve(
      Function<String, @Nullable String> properties, String service, RegionPolicy policy) {
    Objects.requireNonNull(properties);
    Objects.requireNonNull(service);
    Objects.requireNonNull(policy);
    Region region = resolveRegion(properties, policy);
    var transportSecurity = TransportSecurityPolicy.from(properties);
    String endpoint = nonBlank(properties.apply("axiz.aws." + service + ".endpoint"));
    if (endpoint == null) endpoint = nonBlank(properties.apply("axiz.aws.endpoint"));
    if (endpoint != null)
      TransportSecurityPolicy.from(properties)
          .validate(URI.create(endpoint), "AWS endpoint for " + service);
    if (endpoint == null) AwsTransportSecurity.validateOverrides(service, transportSecurity);
    return new AwsClientSettings(
        region, endpoint == null ? null : URI.create(endpoint), transportSecurity);
  }

  public static @Nullable Region resolveRegion(
      Function<String, @Nullable String> properties, RegionPolicy policy) {
    Objects.requireNonNull(properties);
    Objects.requireNonNull(policy);
    String region = nonBlank(properties.apply("axiz.aws.region"));
    if (region == null) region = nonBlank(properties.apply("aws.region"));
    if (region == null) region = nonBlank(properties.apply("AWS_REGION"));
    if (region == null) region = nonBlank(System.getenv("AWS_REGION"));
    if (region == null && policy == RegionPolicy.EXPLICIT)
      throw new IllegalStateException(
          "Async AWS clients require explicit axiz.aws.region, aws.region or AWS_REGION");
    return region == null ? null : Region.of(region);
  }

  public boolean endpointOverridden() {
    return endpoint != null;
  }

  private static @Nullable String nonBlank(@Nullable String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
