package pe.axiz.spring.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.registry.otlp.ExemplarContextProvider;
import io.micrometer.registry.otlp.OtlpExemplarContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.autoconfigure.otlp.OtlpExemplarsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;

@ExtendWith(BlockHoundExtension.class)
class OtlpExemplarStartupTest {
  @BeforeAll
  static void warm() {
    ReactiveInfrastructureBootstrap.initialize();
  }

  @AfterEach
  void noHiddenBlocking() {
    assertTrue(BlockHoundExtension.detectedBlockingCalls().isEmpty());
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizOtlpExemplarsAutoConfiguration.class, OtlpExemplarsAutoConfiguration.class));
  }

  @Test
  void startupCallbackDoesNotResolveTracerUnderTheBeanFactoryLock() {
    runner()
        .withBean(SimpleTracer.class, SimpleTracer::new)
        .withUserConfiguration(Handshake.class)
        .withBean(
            "unresolvedFactory",
            UnknownFactory.class,
            UnknownFactory::new,
            definition -> definition.setLazyInit(true))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(ExemplarContextProvider.class);
              assertEquals("ready", ctx.getBean("handshake"));
              var provider = ctx.getBean(ExemplarContextProvider.class);
              assertThat(provider).isInstanceOf(StartupExemplarContextProvider.class);
              var tracer = ctx.getBean(SimpleTracer.class);
              var span = tracer.nextSpan().start();
              span.context().setSampled(true);
              try (var scope = tracer.withSpan(span)) {
                var exemplar = provider.getExemplarContext();
                assertThat(exemplar).isNotNull();
                assertEquals(span.context().traceId(), exemplar.getTraceId());
                assertEquals(span.context().spanId(), exemplar.getSpanId());
                span.context().setSampled(false);
                assertNull(provider.getExemplarContext());
                span.context().setSampled(null);
                assertNull(provider.getExemplarContext());
              } finally {
                span.end();
              }
              assertNull(provider.getExemplarContext());
            });
  }

  @Test
  void respectsNonePolicyAndApplicationProvidersAndAbsentTracing() {
    runner().run(ctx -> assertThat(ctx).doesNotHaveBean(ExemplarContextProvider.class));
    runner()
        .withBean(SimpleTracer.class, SimpleTracer::new)
        .withPropertyValues("management.tracing.exemplars.include=none")
        .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ExemplarContextProvider.class));
    runner()
        .withBean(SimpleTracer.class, SimpleTracer::new)
        .withPropertyValues("management.tracing.exemplars.include=all")
        .run(
            ctx -> {
              var tracer = ctx.getBean(SimpleTracer.class);
              var span = tracer.nextSpan().start();
              span.context().setSampled(false);
              try (var scope = tracer.withSpan(span)) {
                assertThat(ctx.getBean(ExemplarContextProvider.class).getExemplarContext())
                    .isNotNull();
              } finally {
                span.end();
              }
            });
    ExemplarContextProvider custom = () -> new OtlpExemplarContext("trace", "span");
    runner()
        .withBean(SimpleTracer.class, SimpleTracer::new)
        .withBean(ExemplarContextProvider.class, () -> custom)
        .run(ctx -> assertSame(custom, ctx.getBean(ExemplarContextProvider.class)));
  }

  @Configuration(proxyBeanMethods = false)
  static class Handshake {
    @Bean
    String handshake(ExemplarContextProvider provider) throws Exception {
      var result = new CompletableFuture<OtlpExemplarContext>();
      Thread.ofPlatform()
          .daemon()
          .name("lettuce-nioEventLoop-startup-probe")
          .start(
              () -> {
                try {
                  result.complete(provider.getExemplarContext());
                } catch (Throwable failure) {
                  result.completeExceptionally(failure);
                }
              });
      assertNull(result.get(2, TimeUnit.SECONDS));
      return "ready";
    }
  }

  @SuppressWarnings("rawtypes")
  static class UnknownFactory implements FactoryBean {
    public Object getObject() {
      return new Object();
    }

    public Class<?> getObjectType() {
      return null;
    }
  }
}
