package pe.axiz.spring.r2dbc.aws;

import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.spring.aws.AwsIdentity;

/** Selección del proveedor IAM de AWS con diagnósticos claros del classpath. */
final class IamTokens {
  private IamTokens() {}

  /** Proveedor de tokens para el host y el puerto de una URL R2DBC. */
  static DatabasePasswordProvider provider(
      String r2dbcUrl,
      String username,
      AxizR2dbcAwsProperties iam,
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
}
