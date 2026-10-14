package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import pe.axiz.core.persistence.r2dbc.DatabasePasswordProvider;
import pe.axiz.core.persistence.r2dbc.R2dbcUrls;
import pe.axiz.core.persistence.r2dbc.RdsIamPasswordProvider;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
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
    RdsUtilities rds =
        RdsUtilities.builder()
            .region(region(iam.region() != null ? iam.region() : awsRegion))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();
    return new RdsIamPasswordProvider(
        rds, endpoint.host(), endpoint.port(), username, clock, iam.refreshAfter());
  }

  static Region region(@Nullable String configured) {
    return configured != null && !configured.isBlank()
        ? Region.of(configured)
        : DefaultAwsRegionProviderChain.builder().build().getRegion();
  }

  /**
   * Sets the password option to a publisher evaluated for every new connection. Generating a token
   * is local signing, but refreshing the pod's credentials may call STS, so it runs off the event
   * loop.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static void apply(ConnectionFactoryOptions.Builder options, DatabasePasswordProvider provider) {
    Publisher<CharSequence> password =
        Mono.fromSupplier(provider::password).subscribeOn(Schedulers.boundedElastic());
    options.option((Option) ConnectionFactoryOptions.PASSWORD, password);
  }
}
