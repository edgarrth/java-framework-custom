package pe.axiz.spring.r2dbc;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.aws.identity.WarmedAwsCredentials;
import pe.axiz.adapter.aws.transport.AwsClientSettings;
import pe.axiz.adapter.databaseauth.aws.RdsIamPasswordProvider;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.kit.r2dbc.R2dbcUrls;
import pe.axiz.spring.foundation.aws.AwsCapability;
import pe.axiz.spring.foundation.aws.AwsIdentity;
import pe.axiz.spring.foundation.aws.AwsService;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;

/** Loaded only after the optional AWS authentication adapter has been checked. */
final class AwsIamTokens {
  private AwsIamTokens() {}

  static DatabasePasswordProvider provider(
      String r2dbcUrl,
      String username,
      AxizR2dbcProperties.Iam iam,
      @Nullable String awsRegion,
      Clock clock,
      AwsIdentity identity) {
    R2dbcUrls.Endpoint endpoint = R2dbcUrls.endpoint(r2dbcUrl);
    Region effectiveRegion = region(iam.region(), awsRegion);
    WarmedAwsCredentials credentials =
        new WarmedAwsCredentials(
            identity.source(AwsService.RDS, AwsCapability.R2DBC), clock, Duration.ofSeconds(15));
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

  static Region region(@Nullable String specific, @Nullable String global) {
    return region(specific != null && !specific.isBlank() ? specific : global);
  }

  static Region region(@Nullable String configured) {
    return Objects.requireNonNull(
        AwsClientSettings.resolveRegion(
            key ->
                switch (key) {
                  case "axiz.aws.region" -> configured;
                  case "aws.region" -> System.getProperty("aws.region");
                  default -> null;
                },
            AwsClientSettings.RegionPolicy.EXPLICIT));
  }
}
