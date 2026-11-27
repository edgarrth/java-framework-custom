package pe.axiz.adapter.aws.transport;

import java.util.Locale;
import java.util.Objects;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.awscore.endpoint.AwsClientEndpointProvider;
import software.amazon.awssdk.core.client.builder.SdkClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileFileSystemSetting;

/**
 * Verifica los endpoints configurados en el SDK al arrancar y los destinos efectivos antes de
 * firmar o transmitir.
 */
public final class AwsTransportSecurity implements ExecutionInterceptor {
  private final TransportSecurityPolicy policy;

  public AwsTransportSecurity(TransportSecurityPolicy policy) {
    this.policy = Objects.requireNonNull(policy);
  }

  public static void validateOverrides(String service, TransportSecurityPolicy policy) {
    String id = "secretsmanager".equals(service) ? "secrets_manager" : service;
    AwsClientEndpointProvider.builder()
        .serviceEndpointOverrideEnvironmentVariable(
            "AWS_ENDPOINT_URL_" + id.toUpperCase(Locale.ROOT))
        .serviceEndpointOverrideSystemProperty("aws.endpointUrl." + id)
        .serviceProfileProperty(id)
        .profileName(ProfileFileSystemSetting.AWS_PROFILE.getStringValue().orElse("default"))
        .profileFile(ProfileFile::defaultProfileFile)
        .resolveFromOverrides()
        .ifPresent(endpoint -> policy.validate(endpoint, "AWS SDK endpoint for " + service));
  }

  public static void apply(SdkClientBuilder<?, ?> builder, TransportSecurityPolicy policy) {
    var existing = builder.overrideConfiguration();
    var configuration =
        existing == null ? ClientOverrideConfiguration.builder() : existing.toBuilder();
    builder.overrideConfiguration(
        configuration.addExecutionInterceptor(new AwsTransportSecurity(policy)).build());
  }

  @Override
  public SdkHttpRequest modifyHttpRequest(
      Context.ModifyHttpRequest context, ExecutionAttributes attributes) {
    policy.validate(context.httpRequest().getUri(), "AWS signing destination");
    return context.httpRequest();
  }

  @Override
  public void beforeTransmission(
      Context.BeforeTransmission context, ExecutionAttributes attributes) {
    policy.validate(context.httpRequest().getUri(), "AWS transmission destination");
  }
}
