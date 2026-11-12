package pe.axiz.adapter.aws.identity;

import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.AwsServiceClientConfiguration;
import software.amazon.awssdk.core.SdkPlugin;
import software.amazon.awssdk.core.SdkServiceClientConfiguration;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/** Transfers a credential resource to the SDK client's lifecycle. */
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

  // The SDK closes credential providers, but does not own/close arbitrary SdkPlugin instances.
  // This adapter transfers only the resource; the underlying provider remains encapsulated.
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
