package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import pe.axiz.adapter.observability.aws.WarmedAwsCredentials;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.kit.r2dbc.R2dbcUrls;
import pe.axiz.kit.r2dbc.RdsIamPasswordProvider;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;

/** IAM authentication tokens as the password of R2DBC connections. */
final class IamTokens {

  private IamTokens() {}

  /** A token provider for the host and port of an R2DBC URL. */
  static RdsIamPasswordProvider provider(
      String r2dbcUrl,
      String username,
      AxizR2dbcProperties.Iam iam,
      @Nullable String awsRegion,
      Clock clock) {
    R2dbcUrls.Endpoint endpoint = R2dbcUrls.endpoint(r2dbcUrl);
    Region effectiveRegion = region(iam.region() != null ? iam.region() : awsRegion);
    WarmedAwsCredentials credentials =
        new WarmedAwsCredentials(
            DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build(),
            clock,
            Duration.ofSeconds(15));
    try {
      RdsUtilities rds =
          RdsUtilities.builder().region(effectiveRegion).credentialsProvider(credentials).build();
      RdsIamPasswordProvider provider =
          new RdsIamPasswordProvider(
              rds,
              endpoint.host(),
              endpoint.port(),
              username,
              clock,
              iam.refreshAfter(),
              credentials::close);
      provider.password();
      return provider;
    } catch (RuntimeException | Error failure) {
      credentials.close();
      throw failure;
    }
  }

  static Region region(@Nullable String configured) {
    String region = configured;
    if (region == null || region.isBlank()) region = System.getProperty("aws.region");
    if (region == null || region.isBlank()) region = System.getenv("AWS_REGION");
    if (region == null || region.isBlank())
      throw new IllegalStateException(
          "R2DBC IAM requires an explicit region: axiz.r2dbc.iam.region, axiz.aws.region or"
              + " AWS_REGION");
    return Region.of(region);
  }

  /**
   * Sets the password option to a publisher evaluated for every new connection. Generating a token
   * is local signing with a warmed credential snapshot. Refresh runs independently of connection
   * acquisition; an expired credential fails closed without waiting on network I/O.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static void apply(ConnectionFactoryOptions.Builder options, DatabasePasswordProvider provider) {
    Publisher<CharSequence> password = Mono.fromSupplier(provider::password);
    options.option((Option) ConnectionFactoryOptions.PASSWORD, password);
  }
}
