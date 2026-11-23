package pe.axiz.adapter.aws.identity;

import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.AwsServiceClientConfiguration;
import software.amazon.awssdk.core.SdkPlugin;
import software.amazon.awssdk.core.SdkServiceClientConfiguration;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/** Transfiere un recurso de credenciales al ciclo de vida del cliente del SDK. */
public final class AwsCredentialsPlugin implements SdkPlugin {
  private final AwsCredentialSource source;

  public AwsCredentialsPlugin(AwsCredentialSource source) {
    this.source = Objects.requireNonNull(source);
  }

  @Override
  public void configureClient(SdkServiceClientConfiguration.Builder configuration) {
    if (!(configuration instanceof AwsServiceClientConfiguration.Builder aws))
      throw new IllegalArgumentException("AWS client configuration required");
    aws.credentialsProvider(new ClientResource(source));
  }

  @Override
  public void close() {
    source.close();
  }

  // El SDK cierra los proveedores de credenciales, pero no es dueño ni cierra instancias
  // arbitrarias de SdkPlugin. Este adaptador transfiere solo el recurso; el proveedor subyacente
  // permanece encapsulado.
  private record ClientResource(AwsCredentialSource source)
      implements AwsCredentialsProvider, SdkAutoCloseable {
    @Override
    public AwsCredentials resolveCredentials() {
      return source.provider().resolveCredentials();
    }

    @Override
    public void close() {
      source.close();
    }
  }
}
