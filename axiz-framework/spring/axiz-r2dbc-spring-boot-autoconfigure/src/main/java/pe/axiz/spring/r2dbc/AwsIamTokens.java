package pe.axiz.spring.r2dbc;

import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.databaseauth.aws.RdsIamPasswordProvider;
import pe.axiz.adapter.observability.aws.WarmedAwsCredentials;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.kit.r2dbc.R2dbcUrls;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
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
}
