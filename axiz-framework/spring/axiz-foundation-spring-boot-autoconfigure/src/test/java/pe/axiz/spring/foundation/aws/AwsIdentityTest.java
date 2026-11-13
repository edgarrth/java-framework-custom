package pe.axiz.spring.foundation.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.adapter.aws.identity.WarmedAwsCredentials;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.aws.transport.AwsClients;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.spring.foundation.AxizFoundationAutoConfiguration;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.ResolveIdentityRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.utils.SdkAutoCloseable;

@ExtendWith(OutputCaptureExtension.class)
class AwsIdentityTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class));

  @Test
  void typedScopesPreservePrecedenceAndDoNotTreatForeignQualifiersAsGlobal() {
    runner
        .withUserConfiguration(Identities.class)
        .run(
            ctx -> {
              var identity = ctx.getBean(AwsIdentity.class);
              assertSource(identity, AwsService.SQS, AwsCapability.AUDIT, "audit");
              assertSource(identity, AwsService.SQS, AwsCapability.MESSAGING, "sqs");
              assertSource(identity, AwsService.S3, AwsCapability.STORAGE, "global");
              assertSource(identity, AwsService.KMS, AwsCapability.CRYPTO, "global");
            });
  }

  @Test
  void textQualifiersAndEmptyQualifiersAreNotFrameworkScopesOrGlobal() {
    try (var defaults = mockStatic(AwsCredentialSources.class)) {
      defaults
          .when(() -> AwsCredentialSources.create(TransportSecurityPolicy.system()))
          .thenAnswer(
              x ->
                  AwsCredentialSource.owned(
                      () -> AwsBasicCredentials.create("default", "fixture"), () -> {}));
      runner
          .withUserConfiguration(ForeignQualifiers.class)
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertSource(
                    ctx.getBean(AwsIdentity.class),
                    AwsService.S3,
                    AwsCapability.STORAGE,
                    "default");
                assertSource(
                    ctx.getBean(AwsIdentity.class),
                    AwsService.KMS,
                    AwsCapability.CRYPTO,
                    "default");
              });
      defaults.verify(() -> AwsCredentialSources.create(TransportSecurityPolicy.system()));
    }
  }

  @Test
  void inactiveValidScopesAreAcceptedAndConstructionDoesNotCreateSdkSources() {
    try (var defaults = mockStatic(AwsCredentialSources.class)) {
      runner
          .withUserConfiguration(ScopedOnly.class)
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                ctx.getBean(AwsIdentity.class).factory(AwsService.S3, AwsCapability.STORAGE);
                defaults.verifyNoInteractions();
              });
    }
  }

  @Test
  void invalidDeclarationsAndAmbiguitiesFailAtStartupEvenWithoutConsumers() {
    for (Class<?> config :
        new Class<?>[] {InvalidBoth.class, InvalidNeither.class, Ambiguous.class}) {
      runner.withUserConfiguration(config).run(ctx -> assertThat(ctx).hasFailed());
    }
    runner
        .withUserConfiguration(Primaries.class)
        .run(
            ctx ->
                assertSource(
                    ctx.getBean(AwsIdentity.class),
                    AwsService.S3,
                    AwsCapability.STORAGE,
                    "chosen"));
  }

  @Test
  void programmaticTypedMetadataHonorsNonCandidatesAndSingletonOwnership() {
    runner
        .withInitializer(
            ctx -> {
              var scoped = new RootBeanDefinition(CountingSource.class, CountingSource::new);
              var scope = new AutowireCandidateQualifier(AwsIdentityFor.class);
              scope.setAttribute("service", AwsService.S3);
              scoped.addQualifier(scope);
              scoped.setDefaultCandidate(false);
              ((org.springframework.beans.factory.support.BeanDefinitionRegistry)
                      ctx.getBeanFactory())
                  .registerBeanDefinition("scoped", scoped);
              var hidden = new RootBeanDefinition(CountingSource.class, CountingSource::new);
              hidden.setAutowireCandidate(false);
              ((org.springframework.beans.factory.support.BeanDefinitionRegistry)
                      ctx.getBeanFactory())
                  .registerBeanDefinition("hidden", hidden);
              var explicit = new RootBeanDefinition(CountingSource.class, CountingSource::new);
              explicit.setDefaultCandidate(false);
              ((org.springframework.beans.factory.support.BeanDefinitionRegistry)
                      ctx.getBeanFactory())
                  .registerBeanDefinition("explicit", explicit);
            })
        .run(
            ctx ->
                assertSource(
                    ctx.getBean(AwsIdentity.class),
                    AwsService.S3,
                    AwsCapability.STORAGE,
                    "borrowed"));
    runner
        .withInitializer(
            ctx -> {
              var scoped = new RootBeanDefinition(CountingSource.class, CountingSource::new);
              scoped.setScope("prototype");
              ((org.springframework.beans.factory.support.BeanDefinitionRegistry)
                      ctx.getBeanFactory())
                  .registerBeanDefinition("prototype", scoped);
            })
        .run(ctx -> assertThat(ctx).hasFailed());
    runner
        .withInitializer(
            ctx -> {
              var scoped = new RootBeanDefinition(CountingSource.class, CountingSource::new);
              var scope = new AutowireCandidateQualifier(AwsIdentityFor.class);
              scope.setAttribute("service", "s3");
              scoped.addQualifier(scope);
              ((org.springframework.beans.factory.support.BeanDefinitionRegistry)
                      ctx.getBeanFactory())
                  .registerBeanDefinition("textualTypedScope", scoped);
            })
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void selectionReportIsLazyDeduplicatedAndContainsNoCredentialsOrArn(CapturedOutput output) {
    var calls = new AtomicInteger();
    String key = "fixture-access-key-J1";
    String secret = UUID.randomUUID().toString();
    String arn = "arn:aws:iam::123456789012:role/never-log-this";
    AwsCredentialsProvider provider =
        new AwsCredentialsProvider() {
          public AwsCredentials resolveCredentials() {
            calls.incrementAndGet();
            return AwsBasicCredentials.create(key, secret);
          }

          public String toString() {
            return key + secret + arn;
          }
        };
    runner
        .withBean("restrictedStorage", AwsCredentialsProvider.class, () -> provider)
        .run(
            ctx -> {
              var identity = ctx.getBean(AwsIdentity.class);
              var factory = identity.factory(AwsService.S3, AwsCapability.STORAGE);
              assertEquals(0, calls.get());
              assertFalse(
                  output.getAll().contains("AWS identity selection service=S3 capability=STORAGE"));
              try (var first = factory.get();
                  var second = factory.get()) {
                assertEquals(0, calls.get());
              }
              assertEquals(
                  1,
                  output
                          .getAll()
                          .split("AWS identity selection service=S3 capability=STORAGE", -1)
                          .length
                      - 1);
              assertTrue(output.getAll().contains("level=GLOBAL providerBean=restrictedStorage"));
              assertFalse(output.getAll().contains(key));
              assertFalse(output.getAll().contains(secret));
              assertFalse(output.getAll().contains(arn));
              assertThrows(
                  IllegalArgumentException.class,
                  () -> identity.source(AwsService.UNSPECIFIED, AwsCapability.STORAGE));
              assertThrows(
                  NullPointerException.class, () -> identity.factory(null, AwsCapability.STORAGE));
            });
  }

  @Test
  void nonStandardProviderNamesCannotPutAnArnInTheSelectionReport(CapturedOutput output) {
    String arn = "arn:aws:iam::123456789012:role/not-a-log-label";
    runner
        .withBean(
            arn,
            AwsCredentialsProvider.class,
            () -> () -> AwsBasicCredentials.create("fixture", "fixture"))
        .run(
            ctx -> {
              var identity = ctx.getBean(AwsIdentity.class);
              try (var source = identity.source(AwsService.S3, AwsCapability.STORAGE)) {
                assertFalse(output.getAll().contains(arn));
                assertTrue(output.getAll().contains("providerBean=<redacted-bean-name>"));
              }
              assertThrows(
                  IllegalArgumentException.class,
                  () -> identity.factory(AwsService.S3, AwsCapability.UNSPECIFIED));
              assertThrows(NullPointerException.class, () -> identity.source(AwsService.S3, null));
            });
  }

  @Test
  void borrowedSdkClientAndSnapshotNeverCloseApplicationProvider() {
    var source = new CountingSource();
    runner
        .withBean("application", CountingSource.class, () -> source)
        .run(
            ctx -> {
              var identity = ctx.getBean(AwsIdentity.class);
              try (var owner =
                      AwsAsyncResources.create(
                          "borrowed",
                          1,
                          4,
                          null,
                          identity,
                          AwsService.S3,
                          AwsCapability.STORAGE,
                          AsyncAwsTransportSettings.defaults());
                  var client =
                      AwsClients.build(
                          identity.configure(
                              StsClient.builder(), AwsService.S3, AwsCapability.STORAGE),
                          "us-east-1",
                          "https://127.0.0.1:1",
                          Duration.ofSeconds(3))) {
                assertEquals(
                    "borrowed",
                    client
                        .serviceClientConfiguration()
                        .credentialsProvider()
                        .resolveIdentity()
                        .join()
                        .accessKeyId());
                assertEquals(
                    "borrowed", owner.credentialsProvider().resolveCredentials().accessKeyId());
                assertEquals(0, source.closes.get());
              }
              assertEquals(0, source.closes.get());
            });
    assertEquals(1, source.closes.get());
  }

  @Test
  void sdkIdentityFutureOverridesDoNotBypassTheSnapshotPolicy() {
    var sync = new AtomicInteger();
    var async = new AtomicInteger();
    AwsCredentialsProvider provider =
        new AwsCredentialsProvider() {
          public AwsCredentials resolveCredentials() {
            sync.incrementAndGet();
            return AwsBasicCredentials.create("sync", "fixture");
          }

          public CompletableFuture<AwsCredentialsIdentity> resolveIdentity(
              ResolveIdentityRequest request) {
            async.incrementAndGet();
            return CompletableFuture.completedFuture(
                AwsBasicCredentials.create("async", "fixture"));
          }
        };
    runner
        .withBean("application", AwsCredentialsProvider.class, () -> provider)
        .run(
            ctx -> {
              try (var resource =
                  ctx.getBean(AwsIdentity.class).source(AwsService.S3, AwsCapability.STORAGE)) {
                assertEquals(
                    "sync",
                    resource
                        .provider()
                        .resolveIdentity(ResolveIdentityRequest.builder().build())
                        .join()
                        .accessKeyId());
                assertEquals(1, sync.get());
                assertEquals(0, async.get());
              }
            });
  }

  @Test
  void snapshotsRefreshOutsideReactiveThreadsAndFailClosedAfterContextShutdown() {
    BlockHound.install();
    var source = new CountingSource();
    runner
        .withBean("application", CountingSource.class, () -> source)
        .run(
            ctx -> {
              var identity = ctx.getBean(AwsIdentity.class);
              try (var snapshot =
                  new WarmedAwsCredentials(
                      identity.source(AwsService.S3, AwsCapability.STORAGE),
                      Clock.systemUTC(),
                      Duration.ofDays(1))) {
                assertEquals(
                    "borrowed",
                    Mono.fromSupplier(snapshot::resolveCredentials)
                        .subscribeOn(Schedulers.parallel())
                        .block(Duration.ofSeconds(5))
                        .accessKeyId());
                assertEquals(0, source.nonBlockingCalls.get());
              }
              var factory = identity.factory(AwsService.S3, AwsCapability.STORAGE);
              try (var resource = factory.get()) {
                ctx.close();
                assertThrows(
                    SdkClientException.class, () -> resource.provider().resolveCredentials());
                assertThrows(SdkClientException.class, factory::get);
              }
            });
  }

  @Test
  void initialSnapshotFailureReleasesBorrowWithoutClosingApplicationProviderPrematurely() {
    var source =
        new CountingSource() {
          public AwsCredentials resolveCredentials() {
            throw new IllegalStateException("fixture denied");
          }
        };
    runner
        .withBean("application", CountingSource.class, () -> source)
        .run(
            ctx -> {
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      AwsAsyncResources.create(
                          "failed",
                          1,
                          4,
                          null,
                          ctx.getBean(AwsIdentity.class),
                          AwsService.S3,
                          AwsCapability.STORAGE,
                          AsyncAwsTransportSettings.defaults()));
              assertEquals(0, source.closes.get());
            });
    assertEquals(1, source.closes.get());
  }

  @Test
  void foundationRemainsUsableWithoutAwsClasses() {
    new ApplicationContextRunner()
        .withClassLoader(
            new FilteredClassLoader(
                "software.amazon.awssdk",
                "pe.axiz.adapter.aws",
                "pe.axiz.adapter.observability.aws"))
        .withConfiguration(
            AutoConfigurations.of(
                AxizFoundationAutoConfiguration.class, AxizAwsIdentityAutoConfiguration.class))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasNotFailed()
                    .hasSingleBean(Clock.class)
                    .doesNotHaveBean(AwsIdentity.class));
  }

  private static void assertSource(
      AwsIdentity identity, AwsService service, AwsCapability capability, String expected) {
    try (var source = identity.source(service, capability)) {
      assertEquals(expected, source.provider().resolveCredentials().accessKeyId());
    }
  }

  static class CountingSource implements AwsCredentialsProvider, SdkAutoCloseable {
    final AtomicInteger closes = new AtomicInteger();
    final AtomicInteger nonBlockingCalls = new AtomicInteger();

    public AwsCredentials resolveCredentials() {
      if (Schedulers.isInNonBlockingThread()) nonBlockingCalls.incrementAndGet();
      return AwsBasicCredentials.create("borrowed", "fixture");
    }

    public void close() {
      closes.incrementAndGet();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Identities {
    @Bean
    @AwsIdentityFor(capability = AwsCapability.AUDIT)
    AwsCredentialsProvider audit() {
      return () -> AwsBasicCredentials.create("audit", "fixture");
    }

    @Bean
    @AwsIdentityFor(service = AwsService.SQS)
    AwsCredentialsProvider sqs() {
      return () -> AwsBasicCredentials.create("sqs", "fixture");
    }

    @Bean
    AwsCredentialsProvider global() {
      return () -> AwsBasicCredentials.create("global", "fixture");
    }

    @Bean
    @Qualifier("storage") AwsCredentialsProvider unrelated() {
      return () -> AwsBasicCredentials.create("foreign", "fixture");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ForeignQualifiers {
    @Bean
    @Qualifier("storag") AwsCredentialsProvider typo() {
      return () -> AwsBasicCredentials.create("restricted", "fixture");
    }

    @Bean
    @Qualifier("") AwsCredentialsProvider empty() {
      return () -> AwsBasicCredentials.create("empty", "fixture");
    }

    @Bean
    @ForeignIdentity
    @Primary
    AwsCredentialsProvider custom() {
      return () -> AwsBasicCredentials.create("custom", "fixture");
    }
  }

  @Target({ElementType.METHOD, ElementType.TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @Qualifier @interface ForeignIdentity {}

  @Configuration(proxyBeanMethods = false)
  static class ScopedOnly {
    @Bean
    @AwsIdentityFor(capability = AwsCapability.AUDIT)
    AwsCredentialsProvider audit() {
      return () -> AwsBasicCredentials.create("audit", "fixture");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class InvalidBoth {
    @Bean
    @AwsIdentityFor(service = AwsService.S3, capability = AwsCapability.STORAGE)
    AwsCredentialsProvider invalid() {
      return () -> AwsBasicCredentials.create("invalid", "fixture");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class InvalidNeither {
    @Bean
    @AwsIdentityFor
    AwsCredentialsProvider invalid() {
      return () -> AwsBasicCredentials.create("invalid", "fixture");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Ambiguous {
    @Bean
    @AwsIdentityFor(capability = AwsCapability.AUDIT)
    AwsCredentialsProvider one() {
      return () -> AwsBasicCredentials.create("one", "fixture");
    }

    @Bean
    @AwsIdentityFor(capability = AwsCapability.AUDIT)
    AwsCredentialsProvider two() {
      return () -> AwsBasicCredentials.create("two", "fixture");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Primaries {
    @Bean
    AwsCredentialsProvider other() {
      return () -> AwsBasicCredentials.create("other", "fixture");
    }

    @Bean
    @Primary
    AwsCredentialsProvider chosen() {
      return () -> AwsBasicCredentials.create("chosen", "fixture");
    }
  }
}
