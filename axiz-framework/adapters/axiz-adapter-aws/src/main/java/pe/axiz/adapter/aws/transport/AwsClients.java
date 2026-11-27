package pe.axiz.adapter.aws.transport;

import java.net.URI;
import java.time.Duration;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.adapter.aws.identity.AwsCredentialsPlugin;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.AwsServiceClientConfiguration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.SdkPlugin;
import software.amazon.awssdk.core.SdkServiceClientConfiguration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;

/**
 * Aplica las convenciones del framework a cualquier builder de cliente del AWS SDK v2: región
 * opcional y reemplazo del endpoint (LocalStack en ejecuciones locales y pruebas). Las credenciales
 * siempre siguen {@link AwsCredentialSources} cuando el builder usa los valores por defecto del
 * SDK. Los proveedores personalizados explícitos siguen siendo propiedad de quien llama según el
 * contrato de ciclo de vida del SDK.
 */
public final class AwsClients {

  private AwsClients() {}

  public static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder, @Nullable String region, @Nullable String endpoint) {
    return build(builder, region, endpoint, null);
  }

  public static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder,
      @Nullable String region,
      @Nullable String endpoint,
      @Nullable Duration apiCallTimeout) {
    return build(builder, region, endpoint, apiCallTimeout, TransportSecurityPolicy.system());
  }

  private static <B extends AwsClientBuilder<B, C>, C> C build(
      B builder,
      @Nullable String region,
      @Nullable String endpoint,
      @Nullable Duration apiCallTimeout,
      TransportSecurityPolicy transportSecurity) {
    try {
      if (apiCallTimeout != null && !apiCallTimeout.isPositive())
        throw new IllegalArgumentException("apiCallTimeout must be positive");
      if (apiCallTimeout != null) {
        var existing = builder.overrideConfiguration();
        var overrides =
            existing == null ? ClientOverrideConfiguration.builder() : existing.toBuilder();
        builder.overrideConfiguration(overrides.apiCallTimeout(apiCallTimeout).build());
      }
      if (region != null && !region.isBlank()) {
        builder.region(Region.of(region));
      }
      if (endpoint != null && !endpoint.isBlank()) {
        transportSecurity.validate(URI.create(endpoint), "AWS endpoint");
        builder.endpointOverride(URI.create(endpoint));
      }
      // Un plugin público del SDK ve el proveedor final y preserva los builders explícitos
      // personalizados o estáticos.
      AwsTransportSecurity.apply(builder, transportSecurity);
      var policy = new CredentialPolicy(transportSecurity);
      builder.addPlugin(policy);
      return builder.build();
    } catch (SdkClientException e) {
      closeCredentials(builder, e);
      if (region == null || region.isBlank()) {
        // la cadena del SDK no encontró región: se indica qué configuración le falta al servicio
        throw new IllegalStateException(
            "No AWS region for this client: set axiz.aws.region (or AWS_REGION), or turn off the"
                + " capability that creates it when the service does not use AWS",
            e);
      }
      throw e;
    } catch (RuntimeException | Error failure) {
      closeCredentials(builder, failure);
      throw failure;
    }
  }

  private static void closeCredentials(AwsClientBuilder<?, ?> builder, Throwable failure) {
    for (var plugin : builder.plugins()) {
      try {
        if (plugin instanceof AwsCredentialsPlugin credentials) credentials.close();
        else if (plugin instanceof CredentialPolicy policy) policy.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
    }
  }

  private static final class CredentialPolicy implements SdkPlugin {
    private @Nullable AwsCredentialSource source;
    private final TransportSecurityPolicy transportSecurity;

    private CredentialPolicy(TransportSecurityPolicy transportSecurity) {
      this.transportSecurity = transportSecurity;
    }

    @Override
    public void configureClient(SdkServiceClientConfiguration.Builder configuration) {
      if (configuration instanceof AwsServiceClientConfiguration.Builder aws
          && aws.credentialsProvider() instanceof DefaultCredentialsProvider
          && AwsCredentialSources.irsaDeclared()) {
        source = AwsCredentialSources.create(transportSecurity);
        new AwsCredentialsPlugin(source).configureClient(aws);
      }
    }

    @Override
    public void close() {
      if (source != null) source.close();
    }
  }

  /**
   * Igual que {@link #build}, con región y endpoint tomados de las propiedades del framework:
   * {@code axiz.aws.region}, y {@code axiz.aws.<service>.endpoint} o, en su defecto, {@code
   * axiz.aws.endpoint}. Los módulos que crean un cliente pasan por aquí, de modo que una ejecución
   * local contra LocalStack nunca llega a AWS real porque un módulo omitió el endpoint.
   *
   * @param properties búsqueda de propiedades, por ejemplo {@code environment::getProperty}
   * @param service clave de endpoint del servicio: {@code sqs}, {@code dynamodb}, {@code kms}, etc.
   */
  public static <B extends AwsClientBuilder<B, C>, C> C configured(
      B builder, Function<String, @Nullable String> properties, String service) {
    return configured(builder, properties, service, null);
  }

  public static <B extends AwsClientBuilder<B, C>, C> C configured(
      B builder,
      Function<String, @Nullable String> properties,
      String service,
      @Nullable Duration timeout) {
    try {
      var settings =
          AwsClientSettings.resolve(properties, service, AwsClientSettings.RegionPolicy.SDK_CHAIN);
      var region = settings.region();
      var endpoint = settings.endpoint();
      return build(
          builder,
          region == null ? null : region.id(),
          endpoint == null ? null : endpoint.toString(),
          timeout,
          settings.transportSecurity());
    } catch (RuntimeException | Error failure) {
      closeCredentials(builder, failure);
      throw failure;
    }
  }
}
