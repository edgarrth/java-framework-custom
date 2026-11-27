package pe.axiz.adapter.aws.identity;

import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.awscore.endpoint.AwsClientEndpointProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileFileSystemSetting;

/**
 * Política de credenciales para los clientes AWS propiedad del framework. Cada fuente devuelta
 * pertenece a quien la solicita.
 */
public final class AwsCredentialSources {
  private AwsCredentialSources() {}

  /**
   * Con una declaración IRSA se usa exclusivamente web identity. Las credenciales estáticas de
   * sistema o entorno no pueden reemplazarla, y una web identity fallida no puede derivar a
   * perfiles, contenedores ni IMDS. Sin IRSA se conserva la cadena por defecto del SDK, incluida
   * EKS Pod Identity. La fuente se cierra con su cliente propietario; cerrarla más de una vez es
   * inocuo.
   */
  public static AwsCredentialSource create(TransportSecurityPolicy transportSecurity) {
    // El SDK es dueño de los protocolos IMDS/Pod Identity. Los endpoints configurables de
    // intercambio STS deben usar TLS.
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

  /** Indica si el proceso declara un rol o un archivo de token de web identity. */
  public static boolean irsaDeclared() {
    return SdkSystemSetting.AWS_ROLE_ARN.getStringValue().isPresent()
        || SdkSystemSetting.AWS_WEB_IDENTITY_TOKEN_FILE.getStringValue().isPresent();
  }
}
