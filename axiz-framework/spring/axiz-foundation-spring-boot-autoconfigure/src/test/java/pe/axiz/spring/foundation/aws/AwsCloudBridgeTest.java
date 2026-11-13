package pe.axiz.spring.foundation.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import io.awspring.cloud.autoconfigure.AwsClientProperties;
import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer;
import io.awspring.cloud.autoconfigure.core.AwsConnectionDetails;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;

class AwsCloudBridgeTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizAwsIdentityAutoConfiguration.class,
                  RegionProviderAutoConfiguration.class,
                  AxizAwsCloudBridgeAutoConfiguration.class))
          .withPropertyValues("spring.cloud.aws.region.static=us-east-1");

  private static AwsCredentialsProvider provider(String key) {
    return () -> AwsBasicCredentials.create(key, "fixture");
  }

  private static String cloudIdentity(AwsClientBuilderConfigurer configurer) {
    try (var client = configurer.configure(StsClient.builder()).build()) {
      return client
          .serviceClientConfiguration()
          .credentialsProvider()
          .resolveIdentity()
          .join()
          .accessKeyId();
    }
  }

  @Test
  void scopedProvidersNeverBecomeCloudGlobalAndDefaultIsContextOwned() {
    for (Class<?> configuration : new Class<?>[] {Audit.class, Scoped.class}) {
      var closed = new AtomicInteger();
      var created = new AtomicInteger();
      try (var factory = mockStatic(AwsCredentialSources.class)) {
        factory
            .when(() -> AwsCredentialSources.create(any()))
            .thenAnswer(
                call -> {
                  created.incrementAndGet();
                  return AwsCredentialSource.owned(provider("context"), closed::incrementAndGet);
                });
        runner
            .withUserConfiguration(configuration)
            .run(
                context -> {
                  assertThat(context).hasNotFailed();
                  assertEquals(
                      "context", cloudIdentity(context.getBean(AwsClientBuilderConfigurer.class)));
                  try (var source =
                      context
                          .getBean(AwsIdentity.class)
                          .source(AwsService.S3, AwsCapability.STORAGE)) {
                    assertEquals("context", source.provider().resolveCredentials().accessKeyId());
                  }
                  assertEquals(1, created.get());
                  assertEquals(0, closed.get());
                  assertThat(context.getBeansOfType(AwsCredentialsProvider.class))
                      .doesNotContainKey("awsCredentialsProvider");
                });
      }
      assertEquals(1, closed.get());
    }
  }

  @Test
  void explicitGlobalAndPrimaryAreSelectedWithinTheirScope() {
    for (Class<?> configuration : new Class<?>[] {Global.class, Multiple.class})
      runner
          .withUserConfiguration(configuration)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertEquals(
                    "global", cloudIdentity(context.getBean(AwsClientBuilderConfigurer.class)));
                try (var source =
                    context
                        .getBean(AwsIdentity.class)
                        .source(AwsService.SQS, AwsCapability.AUDIT)) {
                  assertEquals(
                      configuration == Multiple.class ? "audit-primary" : "global",
                      source.provider().resolveCredentials().accessKeyId());
                }
              });
  }

  @Test
  void foreignAutomaticGlobalsAreRejectedAndConnectionDetailsCannotInjectStaticKeys() {
    runner
        .withConfiguration(AutoConfigurations.of(Foreign.class))
        .run(context -> assertThat(context).hasFailed());
    runner
        .withBean(
            AwsConnectionDetails.class,
            () ->
                new AwsConnectionDetails() {
                  @Override
                  public String getAccessKey() {
                    return "fixture";
                  }

                  @Override
                  public String getSecretKey() {
                    return "fixture";
                  }

                  @Override
                  public String getRegion() {
                    return "us-east-1";
                  }
                })
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void rejectsAllCompetingCredentialPropertyFamiliesWithoutLeakingValues() {
    for (String key :
        new String[] {
          "access-key",
          "secret-key",
          "instance-profile",
          "profile.name",
          "profile.path",
          "sts.role-arn",
          "sts.web-identity-token-file"
        }) {
      var failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  AwsCredentialConfiguration.validate(
                      new Binder(
                          new MapConfigurationPropertySource(
                              Map.of(
                                  "spring.cloud.aws.credentials." + key,
                                  "private-fixture-value")))));
      assertThat(failure.getMessage()).doesNotContain("private-fixture-value");
    }
    AwsCredentialConfiguration.validate(
        new Binder(
            new MapConfigurationPropertySource(
                Map.of("spring.cloud.aws.credentials.instance-profile", "false"))));
  }

  @Test
  void cloudAutomaticProviderConfigurationsAreExcludedOnlyWhenManagedAwsIsPresent() {
    var filter = new AwsCloudAutoConfigurationFilter();
    filter.setBeanClassLoader(getClass().getClassLoader());
    assertThat(
            filter.match(
                new String[] {
                  "io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration",
                  "io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration",
                  "io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration"
                },
                null))
        .containsExactly(false, false, true);
  }

  @Test
  void startupEnvironmentRejectsCredentialOverridesAndOptionalClasspathStaysPassive()
      throws Exception {
    var processor = new AwsCredentialEnvironmentPostProcessor();
    assertThrows(
        IllegalStateException.class,
        () ->
            processor.postProcessEnvironment(
                new MockEnvironment()
                    .withProperty("spring.cloud.aws.credentials.profile.name", "fixture"),
                new SpringApplication()));
    try (var loader = new FilteredClassLoader("pe.axiz.adapter.aws")) {
      var filter = new AwsCloudAutoConfigurationFilter();
      filter.setBeanClassLoader(loader);
      assertThat(
              filter.match(
                  new String[] {"io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration", null},
                  null))
          .containsExactly(true, true);
    }
  }

  @Test
  void cloudValidatesTheEffectiveServiceEndpointInsteadOfAnOverriddenGlobal() {
    runner
        .withUserConfiguration(Global.class)
        .withPropertyValues(
            "axiz.config.environment=prod",
            "axiz.transport.allow-plain-http=false",
            "spring.cloud.aws.endpoint=http://unused.invalid")
        .run(
            context -> {
              var configurer = context.getBean(AwsClientBuilderConfigurer.class);
              var settings = new AwsClientProperties() {};
              settings.setEndpoint(URI.create("https://secure.invalid"));
              try (var client = configurer.configure(StsClient.builder(), settings, null).build()) {
                assertEquals(
                    URI.create("https://secure.invalid"),
                    client.serviceClientConfiguration().endpointOverride().orElseThrow());
              }
              assertThrows(
                  IllegalArgumentException.class, () -> configurer.configure(StsClient.builder()));
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class Audit {
    @Bean
    @AwsIdentityFor(capability = AwsCapability.AUDIT)
    AwsCredentialsProvider audit() {
      return provider("audit");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Scoped {
    @Bean
    @AwsIdentityFor(capability = AwsCapability.AUDIT)
    AwsCredentialsProvider audit() {
      return provider("audit");
    }

    @Bean
    @AwsIdentityFor(service = AwsService.KMS)
    AwsCredentialsProvider crypto() {
      return provider("kms");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Global {
    @Bean
    AwsCredentialsProvider global() {
      return provider("global");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Multiple {
    @Bean
    AwsCredentialsProvider global() {
      return provider("global");
    }

    @Bean
    @Primary
    @AwsIdentityFor(capability = AwsCapability.AUDIT)
    AwsCredentialsProvider audit() {
      return provider("audit-primary");
    }

    @Bean
    @AwsIdentityFor(capability = AwsCapability.AUDIT)
    AwsCredentialsProvider secondary() {
      return provider("audit-secondary");
    }
  }

  @AutoConfiguration
  static class Foreign {
    @Bean
    AwsCredentialsProvider foreign() {
      return provider("foreign");
    }
  }
}
