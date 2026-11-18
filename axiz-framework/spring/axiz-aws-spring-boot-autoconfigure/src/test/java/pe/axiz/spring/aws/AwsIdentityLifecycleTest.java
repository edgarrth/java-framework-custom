package pe.axiz.spring.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.utils.SdkAutoCloseable;

@ExtendWith(OutputCaptureExtension.class)
class AwsIdentityLifecycleTest {
  static final AtomicInteger CREATED = new AtomicInteger();
  static final AtomicInteger RELEASED = new AtomicInteger();
  static final AtomicInteger PROVIDERS_CLOSED = new AtomicInteger();
  static final AtomicInteger PREMATURE_CLOSE = new AtomicInteger();
  static volatile CountDownLatch unrelatedStarted = new CountDownLatch(0);
  static volatile CountDownLatch selected = new CountDownLatch(0);

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withUserConfiguration(Infrastructure.class);
  }

  private static void reset() {
    CREATED.set(0);
    RELEASED.set(0);
    PROVIDERS_CLOSED.set(0);
    PREMATURE_CLOSE.set(0);
    unrelatedStarted = new CountDownLatch(0);
    selected = new CountDownLatch(0);
  }

  @Test
  void passiveConstructionDoesNotInstantiateApplicationProvidersOrSdkSources(
      CapturedOutput output) {
    reset();
    try (var defaults = mockStatic(AwsCredentialSources.class)) {
      runner()
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertNotNull(ctx.getBean(AwsIdentity.class));
                assertEquals(0, CREATED.get());
                defaults.verifyNoInteractions();
              });
    }
    assertEquals(0, PROVIDERS_CLOSED.get());
    assertFalse(
        output.getAll().contains("not eligible for getting processed by all BeanPostProcessors"));
  }

  @Test
  void directInfrastructureDependencyKeepsTheApplicationSourceOpenUntilNormalAndFailedCleanup(
      CapturedOutput output) {
    for (boolean fail : new boolean[] {false, true}) {
      reset();
      runner()
          .withUserConfiguration(ConsumerConfiguration.class)
          .withPropertyValues("fixture.fail=" + fail)
          .run(
              ctx -> {
                if (fail) assertThat(ctx).hasFailed();
                else assertThat(ctx).hasNotFailed();
              });
      assertEquals(1, CREATED.get());
      assertEquals(1, RELEASED.get());
      assertEquals(1, PROVIDERS_CLOSED.get());
      assertEquals(0, PREMATURE_CLOSE.get());
    }
    assertFalse(
        output.getAll().contains("not eligible for getting processed by all BeanPostProcessors"));
  }

  @Test
  void parallelStartupHasOnlyRealDependenciesAndClosesEveryLease(CapturedOutput output) {
    reset();
    unrelatedStarted = new CountDownLatch(1);
    selected = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(3)) {
      runner()
          .withInitializer(ctx -> ctx.getBeanFactory().setBootstrapExecutor(executor))
          .withUserConfiguration(BackgroundConfiguration.class)
          .withPropertyValues("fixture.background=true")
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeanFactory().getDependentBeans("applicationProvider"))
                    .contains(AwsIdentity.BEAN_NAME)
                    .doesNotContain("unrelated");
                assertThat(ctx.getBeanFactory().getDependentBeans(AwsIdentity.BEAN_NAME))
                    .doesNotContain("unrelated");
              });
    }
    assertEquals(1, CREATED.get());
    assertEquals(2, RELEASED.get());
    assertEquals(1, PROVIDERS_CLOSED.get());
    assertEquals(0, PREMATURE_CLOSE.get());
    assertFalse(
        output.getAll().contains("not eligible for getting processed by all BeanPostProcessors"));
  }

  static final class Provider implements AwsCredentialsProvider, SdkAutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();

    Provider() {
      CREATED.incrementAndGet();
    }

    public AwsCredentials resolveCredentials() {
      if (closed.get()) {
        PREMATURE_CLOSE.incrementAndGet();
        throw new IllegalStateException("closed provider");
      }
      return AwsBasicCredentials.create("application", "fixture");
    }

    public void close() {
      if (closed.compareAndSet(false, true)) PROVIDERS_CLOSED.incrementAndGet();
    }
  }

  static final class Consumer implements InitializingBean, DisposableBean {
    final boolean fail;
    AwsCredentialSource lease;
    boolean closed;

    Consumer(boolean fail) {
      this.fail = fail;
    }

    public void afterPropertiesSet() {
      if (fail) throw new IllegalStateException("fixture failed pool");
    }

    public void destroy() {
      if (closed) return;
      closed = true;
      assertEquals("application", lease.provider().resolveCredentials().accessKeyId());
      lease.close();
      RELEASED.incrementAndGet();
    }
  }

  static final class Bridge implements DestructionAwareBeanPostProcessor, DisposableBean {
    final AwsIdentity identity;
    final Environment env;
    final Set<Consumer> consumers = ConcurrentHashMap.newKeySet();

    Bridge(AwsIdentity identity, Environment env) {
      this.identity = identity;
      this.env = env;
    }

    public Object postProcessBeforeInitialization(Object bean, String name) {
      if (bean instanceof Consumer consumer) {
        if (env.getProperty("fixture.background", Boolean.class, false)) await(unrelatedStarted);
        consumer.lease = identity.source(AwsService.S3, AwsCapability.STORAGE);
        consumers.add(consumer);
        selected.countDown();
      }
      return bean;
    }

    public boolean requiresDestruction(Object bean) {
      return bean instanceof Consumer;
    }

    public void postProcessBeforeDestruction(Object bean, String name) {
      if (bean instanceof Consumer consumer) {
        consumer.destroy();
        consumers.remove(consumer);
      }
    }

    public void destroy() {
      for (var consumer : consumers) consumer.destroy();
      consumers.clear();
    }
  }

  static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Infrastructure {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Bridge bridge(AwsIdentity identity, Environment env) {
      return new Bridge(identity, env);
    }

    @Bean(bootstrap = Bean.Bootstrap.BACKGROUND)
    @Lazy
    static Provider applicationProvider() {
      return new Provider();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ConsumerConfiguration {
    @Bean
    Consumer consumer(Environment env) {
      return new Consumer(env.getProperty("fixture.fail", Boolean.class, false));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class BackgroundConfiguration {
    @Bean(bootstrap = Bean.Bootstrap.BACKGROUND)
    Consumer first() {
      return new Consumer(false);
    }

    @Bean(bootstrap = Bean.Bootstrap.BACKGROUND)
    Consumer second() {
      return new Consumer(false);
    }

    @Bean(bootstrap = Bean.Bootstrap.BACKGROUND)
    Object unrelated() {
      unrelatedStarted.countDown();
      await(selected);
      return new Object();
    }
  }
}
