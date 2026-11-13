package pe.axiz.spring.config;

import io.awspring.cloud.autoconfigure.AwsSyncClientCustomizer;
import io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreConfigDataLocationResolver;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.bootstrap.BootstrapContext;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.Profiles;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.adapter.aws.transport.AwsTransportSecurity;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.spring.foundation.aws.AwsCredentialConfiguration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.services.ssm.SsmClient;

/** Keeps the standard aws-parameterstore: format while controlling identity before first I/O. */
public final class AxizParameterStoreResolver
    implements ConfigDataLocationResolver<AxizParameterStoreResource>, Ordered {
  private final DeferredLogFactory logs;

  public AxizParameterStoreResolver(DeferredLogFactory logs) {
    this.logs = logs;
  }

  @Override
  public int getOrder() {
    return -1;
  }

  @Override
  public boolean isResolvable(
      ConfigDataLocationResolverContext context, ConfigDataLocation location) {
    return location.hasPrefix("aws-parameterstore:")
        && ClassUtils.isPresent(
            "io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreConfigDataLocationResolver",
            getClass().getClassLoader());
  }

  @Override
  public List<AxizParameterStoreResource> resolve(
      ConfigDataLocationResolverContext context, ConfigDataLocation location) {
    return List.of();
  }

  @Override
  public List<AxizParameterStoreResource> resolveProfileSpecific(
      ConfigDataLocationResolverContext context, ConfigDataLocation location, Profiles profiles) {
    return Cloud.resolve(logs, context, location, profiles);
  }

  private static final class Cloud {
    private Cloud() {}

    static List<AxizParameterStoreResource> resolve(
        DeferredLogFactory logs,
        ConfigDataLocationResolverContext context,
        ConfigDataLocation location,
        Profiles profiles) {
      var binder = context.getBinder();
      AwsCredentialConfiguration.validate(binder);
      var policy = TransportSecurityPolicy.from(key -> binder.bind(key, String.class).orElse(null));
      URI endpoint =
          binder
              .bind("spring.cloud.aws.parameterstore.endpoint", URI.class)
              .orElseGet(() -> binder.bind("spring.cloud.aws.endpoint", URI.class).orElse(null));
      if (endpoint != null) policy.validate(endpoint, "Parameter Store effective endpoint");
      else AwsTransportSecurity.validateOverrides("ssm", policy);
      var bootstrap = context.getBootstrapContext();
      if (!bootstrap.isRegistered(AwsBootstrapOwner.class)) {
        var owner =
            new AwsBootstrapOwner(
                () -> {
                  var source = AwsCredentialSources.create(policy);
                  return new AwsBootstrapOwner.Lease(source.provider(), source::close);
                });
        bootstrap.register(AwsBootstrapOwner.class, ignored -> owner);
        bootstrap.registerIfAbsent(
            AwsCredentialsProvider.class, ignored -> (AwsCredentialsProvider) owner.provider());
        var previous = bootstrap.getRegisteredInstanceSupplier(AwsSyncClientCustomizer.class);
        bootstrap.register(
            AwsSyncClientCustomizer.class,
            ctx ->
                builder -> {
                  var customizer = previous == null ? null : previous.get(ctx);
                  if (customizer != null) customizer.customize(builder);
                  AwsTransportSecurity.apply((AwsClientBuilder<?, ?>) builder, policy);
                });
        bootstrap.addCloseListener(event -> owner.transfer(event.getApplicationContext()));
      }
      var resourcesOwner =
          Objects.requireNonNull(
              bootstrap.get(AwsBootstrapOwner.class), "Managed bootstrap owner is required");
      var resources =
          new ParameterStoreConfigDataLocationResolver(logs) {
            @Override
            protected SsmClient createSimpleSystemManagementClient(BootstrapContext ctx) {
              SsmClient client = super.createSimpleSystemManagementClient(ctx);
              resourcesOwner.ownsClient(client::close);
              return client;
            }
          }.resolveProfileSpecific(context, location, profiles);
      return resources.stream()
          .map(resource -> new AxizParameterStoreResource(resource, resource.isOptional()))
          .toList();
    }
  }
}
