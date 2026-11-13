package pe.axiz.adapter.aws.identity;

import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.awscore.endpoint.AwsClientEndpointProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileFileSystemSetting;

/**
 * Credential policy for framework-owned AWS clients. Each returned source belongs to its caller.
 */
public final class AwsCredentialSources {
  private AwsCredentialSources() {}

  /**
   * With an IRSA declaration, use web identity exclusively. Static system/environment credentials
   * cannot override it, and a failed web identity cannot fall through to profiles, containers or
   * IMDS. Without IRSA, retain the SDK default chain, including EKS Pod Identity. Close the source
   * with its owning client; closing more than once is harmless.
   */
  public static AwsCredentialSource create(TransportSecurityPolicy transportSecurity) {
    // The SDK owns IMDS/Pod Identity protocols. Configurable STS exchange endpoints must use TLS.
    AwsClientEndpointProvider.builder()
        .serviceEndpointOverrideEnvironmentVariable("AWS_ENDPOINT_URL_STS")
        .serviceEndpointOverrideSystemProperty("aws.endpointUrl.sts")
        .serviceProfileProperty("sts")
        .profileName(ProfileFileSystemSetting.AWS_PROFILE.getStringValue().orElse("default"))
        .profileFile(ProfileFile::defaultProfileFile)
        .resolveFromOverrides()
        .ifPresent(endpoint -> transportSecurity.validate(endpoint, "STS endpoint"));
    var role = SdkSystemSetting.AWS_ROLE_ARN.getStringValue();
    var token = SdkSystemSetting.AWS_WEB_IDENTITY_TOKEN_FILE.getStringValue();
    if (role.isPresent() || token.isPresent()) {
      if (role.filter(value -> !value.isBlank()).isEmpty()
          || token.filter(value -> !value.isBlank()).isEmpty()) {
        throw new IllegalStateException(
            "IRSA requires both AWS_ROLE_ARN and AWS_WEB_IDENTITY_TOKEN_FILE");
      }
      var source =
          WebIdentityTokenFileCredentialsProvider.builder()
              .asyncCredentialUpdateEnabled(true)
              .build();
      return AwsCredentialSource.owned(source, source::close);
    }
    var source = DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build();
    return AwsCredentialSource.owned(source, source::close);
  }

  /** Whether the process declares a role or web identity token file. */
  public static boolean irsaDeclared() {
    return SdkSystemSetting.AWS_ROLE_ARN.getStringValue().isPresent()
        || SdkSystemSetting.AWS_WEB_IDENTITY_TOKEN_FILE.getStringValue().isPresent();
  }
}
