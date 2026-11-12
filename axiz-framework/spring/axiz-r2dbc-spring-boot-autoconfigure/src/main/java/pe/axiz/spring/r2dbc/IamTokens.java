package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.util.ClassUtils;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.spring.foundation.aws.AwsIdentity;
import reactor.core.publisher.Mono;

/** IAM authentication tokens as the password of R2DBC connections. */
final class IamTokens {

  private IamTokens() {}

  /** A token provider for the host and port of an R2DBC URL. */
  static DatabasePasswordProvider provider(
      String r2dbcUrl,
      String username,
      AxizR2dbcProperties.Iam iam,
      @Nullable String awsRegion,
      Clock clock,
      @Nullable ClassLoader classLoader,
      AwsIdentity identity) {
    if (!ClassUtils.isPresent(
            "pe.axiz.adapter.databaseauth.aws.RdsIamPasswordProvider", classLoader)
        || !ClassUtils.isPresent("software.amazon.awssdk.services.rds.RdsUtilities", classLoader))
      throw new IllegalStateException(
          "R2DBC IAM requires runtime dependency pe.axiz:axiz-adapter-database-auth-aws"
              + " or an application DatabasePasswordProvider");
    return AwsIamTokens.provider(r2dbcUrl, username, iam, awsRegion, clock, identity);
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
