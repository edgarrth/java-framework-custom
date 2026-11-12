package pe.axiz.adapter.aws.identity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/** A credential resource with uniform, idempotent release and a non-closing SDK view. */
public final class AwsCredentialSource implements SdkAutoCloseable {
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Runnable release;
  private final AwsCredentialsProvider view;

  private AwsCredentialSource(AwsCredentialsProvider provider, Runnable release) {
    Objects.requireNonNull(provider);
    this.release = Objects.requireNonNull(release);
    view =
        () -> {
          if (closed.get()) throw SdkClientException.create("AWS credential resource is closed");
          return provider.resolveCredentials();
        };
  }

  /** Transfers the supplied cleanup responsibility to this resource. */
  public static AwsCredentialSource owned(AwsCredentialsProvider provider, Runnable release) {
    return new AwsCredentialSource(provider, release);
  }

  /** Closing this resource invalidates its view without closing the provider's owner. */
  public static AwsCredentialSource borrowed(AwsCredentialsProvider provider) {
    return new AwsCredentialSource(provider, () -> {});
  }

  public AwsCredentialsProvider provider() {
    return view;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) release.run();
  }
}
