package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.secrets.AxizSecretsAutoConfiguration;
import pe.axiz.spring.secrets.ReactiveSecrets;
import pe.axiz.spring.secrets.aws.AxizSecretsAwsAutoConfiguration;
import pe.axiz.spring.secrets.aws.AxizSecretsNativeAwsAutoConfiguration;
import pe.axiz.spring.secrets.file.AxizSecretsFileAutoConfiguration;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/**
 * Native secret lookup through the actual Spring chain, with cold I/O and non-noop observations.
 */
@ExtendWith(BlockHoundExtension.class)
class LocalStackSecretsIT {
  private static final LocalStackContainer LOCALSTACK =
      TestContainerImages.localstack().withServices("secretsmanager", "ssm");
  private static final ObservationRegistry OBSERVATIONS = ObservationRegistry.create();
  private static final AtomicInteger MANAGER_GETS = new AtomicInteger();
  private static final AtomicInteger PARAMETER_GETS = new AtomicInteger();
  private static final AtomicInteger REFRESHES = new AtomicInteger();
  private static final Queue<Observation.Context> AWS_CONTEXTS = new ConcurrentLinkedQueue<>();
  private static SecretsManagerClient setupManager;
  private static SsmClient setupParameters;
  private static SecretsManagerAsyncClient manager;
  private static SsmAsyncClient parameters;
  private static AsyncAwsClients resources;
  private static NettyLifecycleProbe lifecycle;

  @BeforeAll
  static void start() {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    LOCALSTACK.start();
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
    setupManager =
        SecretsManagerClient.builder()
            .endpointOverride(LOCALSTACK.getEndpoint())
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    setupParameters =
        SsmClient.builder()
            .endpointOverride(LOCALSTACK.getEndpoint())
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    setupManager.createSecret(
        b -> b.name("native/database").secretString("{\"password\":\"version-one\"}"));
    setupManager.createSecret(b -> b.name("native/shared").secretString("manager-first"));
    setupManager.createSecret(
        b -> b.name("native/malformed").secretString("sensitive-invalid-json"));
    setupParameters.putParameter(
        b -> b.name("/native/shared").type(ParameterType.SECURE_STRING).value("parameter-second"));
    setupParameters.putParameter(
        b ->
            b.name("/native/parameter-only")
                .type(ParameterType.SECURE_STRING)
                .value("secure-parameter"));
    setupParameters.putParameter(
        b ->
            b.name("/native/alternate/password")
                .type(ParameterType.SECURE_STRING)
                .value("field-parameter"));
    OBSERVATIONS
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              public void onStart(Observation.Context context) {
                if (context.getLowCardinalityKeyValue(AxizAttributes.AWS_OPERATION) != null)
                  AWS_CONTEXTS.add(context);
              }

              public void onStop(Observation.Context context) {
                var operation = context.getLowCardinalityKeyValue(AxizAttributes.AWS_OPERATION);
                if (operation == null) return;
                if ("GetSecretValue".equals(operation.getValue())) MANAGER_GETS.incrementAndGet();
                if ("GetParameter".equals(operation.getValue())) PARAMETER_GETS.incrementAndGet();
              }
            });
    lifecycle = new NettyLifecycleProbe();
    resources =
        new AsyncAwsClients(
            pe.axiz.adapter.aws.identity.AwsCredentialSource.borrowed(
                () -> {
                  REFRESHES.incrementAndGet();
                  assertTrue(
                      !Thread.currentThread().getName().contains("Netty")
                          && !Thread.currentThread().getName().startsWith("parallel-"));
                  LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
                  return credentials.resolveCredentials();
                }),
            Clock.systemUTC(),
            Duration.ofMillis(25),
            AsyncAwsTransportSettings.defaults());
    Function<String, String> properties =
        key ->
            switch (key) {
              case "axiz.aws.region" -> LOCALSTACK.getRegion();
              case "axiz.config.environment" -> "test";
              case "axiz.transport.allow-plain-http" -> "true";
              case "axiz.aws.endpoint" -> LOCALSTACK.getEndpoint().toString();
              case "axiz.aws.async.connection-ttl", "axiz.aws.async.max-idle-time" -> "100ms";
              default -> null;
            };
    manager =
        AwsObservability.configured(
            resources,
            SecretsManagerAsyncClient.builder(),
            OBSERVATIONS,
            properties,
            "secretsmanager");
    parameters =
        AwsObservability.configured(
            resources, SsmAsyncClient.builder(), OBSERVATIONS, properties, "ssm");
  }

  @AfterAll
  static void stop() {
    if (manager != null) manager.close();
    if (parameters != null) parameters.close();
    if (resources != null) resources.close();
    if (setupManager != null) setupManager.close();
    if (setupParameters != null) setupParameters.close();
    LOCALSTACK.stop();
    if (lifecycle != null) {
      lifecycle.close();
      assertTrue(lifecycle.errors.isEmpty(), lifecycle.errors.toString());
    }
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
  }

  private static ReactiveWebApplicationContextRunner context(Path directory, Clock clock) {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizSecretsAwsAutoConfiguration.class,
                AxizSecretsNativeAwsAutoConfiguration.class,
                AxizSecretsFileAutoConfiguration.class,
                AxizSecretsAutoConfiguration.class))
        .withPropertyValues(
            "axiz.secrets.directory=" + directory,
            "axiz.secrets.environment=false",
            "axiz.secrets.cache-ttl=1m",
            "axiz.aws.region=" + LOCALSTACK.getRegion(),
            "axiz.secrets.secrets-manager.enabled=true",
            "axiz.secrets.secrets-manager.prefix=native/",
            "axiz.secrets.parameter-store.enabled=true",
            "axiz.secrets.parameter-store.path=/native/")
        .withBean(Clock.class, () -> clock)
        .withBean(
            SecretsManagerClient.class, () -> setupManager, bean -> bean.setDestroyMethodName(""))
        .withBean(SsmClient.class, () -> setupParameters, bean -> bean.setDestroyMethodName(""))
        .withBean(
            SecretsManagerAsyncClient.class, () -> manager, bean -> bean.setDestroyMethodName(""))
        .withBean(SsmAsyncClient.class, () -> parameters, bean -> bean.setDestroyMethodName(""));
  }

  @Test
  void mountedSnapshotAndNativeCacheRotateWithoutBlockingRequests(@TempDir Path directory)
      throws Exception {
    Path mounted = directory.resolve("mounted");
    Files.writeString(mounted, "prefetched");
    var clock = new MovingClock();
    context(directory, clock)
        .run(
            ctx -> {
              var secrets = ctx.getBean(ReactiveSecrets.class);
              Files.delete(mounted);
              StepVerifier.create(secrets.require("mounted").subscribeOn(Schedulers.parallel()))
                  .assertNext(value -> assertEquals("prefetched", value.reveal()))
                  .expectComplete()
                  .verify(Duration.ofSeconds(10));
              int before = MANAGER_GETS.get();
              StepVerifier.create(
                      Flux.range(0, 40)
                          .flatMap(i -> secrets.require("database#password"), 20)
                          .doOnNext(
                              value -> {
                                assertEquals("version-one", value.reveal());
                                assertNull(
                                    OBSERVATIONS.getCurrentObservation(),
                                    "SDK scope leaked into a completion callback");
                              })
                          .subscribeOn(Schedulers.parallel()))
                  .expectNextCount(40)
                  .expectComplete()
                  .verify(Duration.ofSeconds(30));
              assertEquals(
                  1,
                  MANAGER_GETS.get() - before,
                  "concurrent misses must share one native request");
              setupManager.putSecretValue(
                  b ->
                      b.secretId("native/database").secretString("{\"password\":\"version-two\"}"));
              clock.now = clock.now.plusSeconds(60);
              StepVerifier.create(
                      Mono.delay(Duration.ofMillis(150))
                          .then(secrets.require("database#password"))
                          .subscribeOn(Schedulers.parallel()))
                  .assertNext(value -> assertEquals("version-two", value.reveal()))
                  .expectComplete()
                  .verify(Duration.ofSeconds(30));
              assertEquals(2, MANAGER_GETS.get() - before);
              assertTrue(REFRESHES.get() > 1);
            });
  }

  @Test
  void orderedFallbackSupportsSecureStringAndFieldAliases(@TempDir Path directory) {
    context(directory, Clock.systemUTC())
        .run(
            ctx -> {
              var secrets = ctx.getBean(ReactiveSecrets.class);
              StepVerifier.create(secrets.require("shared").subscribeOn(Schedulers.parallel()))
                  .assertNext(value -> assertEquals("manager-first", value.reveal()))
                  .expectComplete()
                  .verify(Duration.ofSeconds(30));
              StepVerifier.create(
                      secrets.require("parameter-only").subscribeOn(Schedulers.parallel()))
                  .assertNext(value -> assertEquals("secure-parameter", value.reveal()))
                  .expectComplete()
                  .verify(Duration.ofSeconds(30));
              StepVerifier.create(
                      secrets.require("alternate#password").subscribeOn(Schedulers.parallel()))
                  .assertNext(value -> assertEquals("field-parameter", value.reveal()))
                  .expectComplete()
                  .verify(Duration.ofSeconds(30));
              StepVerifier.create(secrets.require("missing").subscribeOn(Schedulers.parallel()))
                  .expectError(SecretNotFoundException.class)
                  .verify(Duration.ofSeconds(30));
            });
  }

  @Test
  void fallbackAndCoalescedSubscribersRetainTheirOwnParent(@TempDir Path directory) {
    context(directory, Clock.systemUTC())
        .run(
            ctx -> {
              var secrets = ctx.getBean(ReactiveSecrets.class);
              var first = Observation.start("request-first", OBSERVATIONS);
              var second = Observation.start("request-second", OBSERVATIONS);
              AWS_CONTEXTS.clear();
              try {
                StepVerifier.create(
                        Flux.merge(
                                secrets
                                    .require("parameter-only")
                                    .doOnNext(
                                        value -> {
                                          assertEquals("secure-parameter", value.reveal());
                                          assertSame(first, OBSERVATIONS.getCurrentObservation());
                                        })
                                    .contextWrite(
                                        Context.of(ObservationThreadLocalAccessor.KEY, first)),
                                secrets
                                    .require("parameter-only")
                                    .doOnNext(
                                        value -> {
                                          assertEquals("secure-parameter", value.reveal());
                                          assertSame(second, OBSERVATIONS.getCurrentObservation());
                                        })
                                    .contextWrite(
                                        Context.of(ObservationThreadLocalAccessor.KEY, second)))
                            .subscribeOn(Schedulers.parallel()))
                    .expectNextCount(2)
                    .expectComplete()
                    .verify(Duration.ofSeconds(30));
                assertEquals(
                    2, AWS_CONTEXTS.size(), "one shared Secrets Manager miss and one SSM hit");
                for (var sdk : AWS_CONTEXTS) assertSame(first, sdk.getParentObservation());
                assertNull(OBSERVATIONS.getCurrentObservation());
              } finally {
                first.stop();
                second.stop();
              }
            });
  }

  @Test
  void malformedJsonDoesNotFallThroughOrExposeItsValue(@TempDir Path directory) {
    context(directory, Clock.systemUTC())
        .run(
            ctx -> {
              int parametersBefore = PARAMETER_GETS.get();
              StepVerifier.create(
                      ctx.getBean(ReactiveSecrets.class)
                          .require("malformed#password")
                          .subscribeOn(Schedulers.parallel()))
                  .expectErrorMatches(
                      failure ->
                          failure instanceof IllegalStateException
                              && !failure.toString().contains("sensitive-invalid-json")
                              && failure.getCause() == null)
                  .verify(Duration.ofSeconds(30));
              assertEquals(parametersBefore, PARAMETER_GETS.get());
            });
  }

  private static final class MovingClock extends Clock {
    volatile Instant now = Instant.now();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }
  }
}
