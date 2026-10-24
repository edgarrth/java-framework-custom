package pe.axiz.spring.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.core.observability.AttributeSanitizer;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.observability.StructuredEventEmitter;

/** Defaults for export and probes, and what every observation carries or skips. */
class ObservabilityConventionsTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizObservabilityAutoConfiguration.class));

  /** Records the observations that actually start. */
  static final class Recorder implements ObservationHandler<Observation.Context> {
    final List<Observation.Context> started = new ArrayList<>();

    @Override
    public void onStart(Observation.Context context) {
      started.add(context);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }
  }

  @Test
  void exportAndProbeDefaultsNeverOverrideTheService() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("management.tracing.sampling.probability", "0.1")
            .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318");
    AxizObservabilityEnvironmentPostProcessor epp = new AxizObservabilityEnvironmentPostProcessor();
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals("0.1", env.getProperty("management.tracing.sampling.probability"));
    assertEquals(
        "http://collector:4318/v1/traces",
        env.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"));
    assertEquals("health,info", env.getProperty("management.endpoints.web.exposure.include"));
    assertEquals("never", env.getProperty("management.endpoint.health.show-details"));
    assertEquals("false", env.getProperty("management.info.env.enabled"));
    assertEquals(Ordered.LOWEST_PRECEDENCE, epp.getOrder());

    MockEnvironment local = new MockEnvironment();
    epp.postProcessEnvironment(local, new SpringApplication());
    assertEquals(
        "http://localhost:4318/v1/metrics",
        local.getProperty("management.otlp.metrics.export.url"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theRegistryGetsTheRequestContextAndSkipsProbes() {
    runner.run(
        ctx -> {
          ObservationRegistry registry = ObservationRegistry.create();
          Recorder recorder = new Recorder();
          registry.observationConfig().observationHandler(recorder);
          ctx.getBean(ObservationRegistryCustomizer.class).customize(registry);

          RequestMetadata m =
              RequestMetadata.builder("corr-12345678", "req-12345678")
                  .channel("web")
                  .tenantId("bank-a")
                  .build();
          try (RequestContext.Scope ignored = RequestContext.open(m)) {
            Observation.createNotStarted("axiz.payment", registry).start().stop();
          }
          Observation.Context payment = recorder.started.get(0);
          assertEquals(
              "bank-a", payment.getHighCardinalityKeyValue(AxizAttributes.TENANT).getValue());

          ServerRequestObservationContext probe =
              new ServerRequestObservationContext(
                  MockServerHttpRequest.get("/actuator/health").build(),
                  new MockServerHttpResponse(),
                  Map.of());
          Observation.createNotStarted("http.server.requests", () -> probe, registry)
              .start()
              .stop();
          assertEquals(1, recorder.started.size(), "the probe is not observed");
        });
  }

  @Test
  @SuppressWarnings("unchecked")
  void contextAttributesAndIgnoredPathsCanBeTurnedOff() {
    runner
        .withPropertyValues(
            "axiz.observability.context-attributes=false", "axiz.observability.ignored-paths=")
        .run(
            ctx -> {
              ObservationRegistry registry = ObservationRegistry.create();
              Recorder recorder = new Recorder();
              registry.observationConfig().observationHandler(recorder);
              ctx.getBean(ObservationRegistryCustomizer.class).customize(registry);
              try (RequestContext.Scope ignored =
                  RequestContext.open(
                      RequestMetadata.builder("corr-12345678", "req-12345678").build())) {
                Observation.createNotStarted("axiz.payment", registry).start().stop();
              }
              assertNull(
                  recorder
                      .started
                      .get(0)
                      .getHighCardinalityKeyValue(AxizAttributes.CORRELATION_ID));
              ServerRequestObservationContext probe =
                  new ServerRequestObservationContext(
                      MockServerHttpRequest.get("/actuator/health").build(),
                      new MockServerHttpResponse(),
                      Map.of());
              Observation.createNotStarted("http.server.requests", () -> probe, registry)
                  .start()
                  .stop();
              assertEquals(2, recorder.started.size());
            });
  }

  @Test
  void withoutARequestTheObservationIsUntouched() {
    Observation.Context context = new Observation.Context();
    new RequestContextObservationFilter().map(context);
    assertFalse(context.getHighCardinalityKeyValues().iterator().hasNext());
  }

  @Test
  void onlyBusinessValidationAndSecurityFailuresAreRejections() {
    assertTrue(
        AxizObservabilityAutoConfiguration.REJECTIONS.apply(
            new ValidationException(List.of(FieldViolation.of("amount", "Min", "too small")))));
    assertTrue(
        AxizObservabilityAutoConfiguration.REJECTIONS.apply(AccessException.forbidden("no")));
    assertFalse(
        AxizObservabilityAutoConfiguration.REJECTIONS.apply(
            new AxizException(CoreErrors.UNEXPECTED, "boom")));
  }

  @Test
  void deniedAttributesAreConfigurableAndTheConventionsCanBeSwitchedOff() {
    runner
        .withPropertyValues("axiz.observability.denied-attributes=customer.document")
        .run(
            ctx -> {
              AttributeSanitizer sanitizer = ctx.getBean(AttributeSanitizer.class);
              assertThat(
                      sanitizer.sanitize(Map.of("customer.document", "12345678", "step", "debit")))
                  .doesNotContainKey("customer.document")
                  .containsEntry("step", "debit");
              assertThat(ctx).hasSingleBean(StructuredEventEmitter.class);
            });
    runner
        .withPropertyValues("axiz.observability.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(AttributeSanitizer.class));
  }
}
