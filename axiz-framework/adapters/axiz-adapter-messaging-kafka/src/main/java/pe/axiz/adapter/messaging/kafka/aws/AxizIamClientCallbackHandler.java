package pe.axiz.adapter.messaging.kafka.aws;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.msk.auth.iam.IAMClientCallbackHandler;
import software.amazon.msk.auth.iam.internals.AWSCredentialsCallback;
import software.amazon.msk.auth.iam.internals.region.ConfigurableRegionProvider;

/** MSK authentication with an owned, exclusive web identity source when IRSA is declared. */
public final class AxizIamClientCallbackHandler implements AuthenticateCallbackHandler {
  /** In-memory credential resource factory; never a string/YAML credential setting. */
  public static final String CREDENTIALS_SOURCE_FACTORY_CONFIG =
      "axiz.msk.credentials.source-factory";

  private @Nullable AwsCredentialSource source;
  private @Nullable IAMClientCallbackHandler delegate;
  private @Nullable ConfigurableRegionProvider region;

  @Override
  public void configure(
      Map<String, ?> configs, String mechanism, List<AppConfigurationEntry> entries) {
    close();
    Object supplied = configs.get(CREDENTIALS_SOURCE_FACTORY_CONFIG);
    if (supplied != null && !(supplied instanceof Supplier<?>))
      throw new IllegalArgumentException("Expected a credential resource supplier");
    if (supplied == null && !AwsCredentialSources.irsaDeclared()) {
      var configured = new IAMClientCallbackHandler();
      configured.configure(configs, mechanism, entries);
      delegate = configured;
      return;
    }
    if (!"AWS_MSK_IAM".equals(mechanism))
      throw new IllegalArgumentException("Expected AWS_MSK_IAM");
    var options =
        entries.stream()
            .filter(
                entry ->
                    "software.amazon.msk.auth.iam.IAMLoginModule"
                        .equals(entry.getLoginModuleName()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("IAMLoginModule is required"))
            .getOptions();
    if (options.containsKey("awsProfileName") || options.containsKey("awsRoleArn"))
      throw new IllegalArgumentException(
          "Explicit framework credentials or IRSA cannot be combined with a different MSK profile"
              + " or role");
    Object customRegion = options.get("awsMskRegionProvider");
    region =
        customRegion instanceof String value ? ConfigurableRegionProvider.getInstance(value) : null;
    if (supplied != null) {
      Object value = ((Supplier<?>) supplied).get();
      if (!(value instanceof AwsCredentialSource resource))
        throw new IllegalArgumentException("Credential factory must return an AwsCredentialSource");
      source = resource;
    } else source = AwsCredentialSources.create(TransportSecurityPolicy.system());
  }

  @Override
  public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
    IAMClientCallbackHandler currentDelegate = delegate;
    if (currentDelegate != null) {
      currentDelegate.handle(callbacks);
      return;
    }
    AwsCredentialSource current = source;
    if (current == null) throw new IllegalStateException("IAM callback handler is not configured");
    for (Callback callback : callbacks) {
      if (!(callback instanceof AWSCredentialsCallback credentials))
        throw new UnsupportedCallbackException(callback);
      try {
        credentials.setAwsCredentials(current.provider().resolveCredentials());
        credentials.setCustomRegionProvider(region);
      } catch (RuntimeException failure) {
        credentials.setLoadingException(failure);
      }
    }
  }

  @Override
  public void close() {
    AwsCredentialSource current = source;
    source = null;
    if (current != null) current.close();
    IAMClientCallbackHandler currentDelegate = delegate;
    delegate = null;
    if (currentDelegate != null) currentDelegate.close();
    region = null;
  }
}
