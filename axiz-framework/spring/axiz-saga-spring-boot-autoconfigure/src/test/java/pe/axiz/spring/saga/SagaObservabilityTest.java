package pe.axiz.spring.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.saga.InMemorySagaStateStore;
import pe.axiz.core.saga.SagaListener;
import pe.axiz.core.saga.SagaStateStore;
import pe.axiz.core.saga.SagaStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class SagaObservabilityTest {
  @Test
  void lifecycleMetricsAreWiredAndNeverTagInstanceIdsOrFailureText() {
    var meters = new SimpleMeterRegistry();
    try {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSagaAutoConfiguration.class))
          .withBean(SagaStateStore.class, InMemorySagaStateStore::new)
          .withBean(SimpleMeterRegistry.class, () -> meters)
          .run(
              context -> {
                assertThat(context).hasSingleBean(SagaListener.class);
                var listener = context.getBean(SagaListener.class);
                listener.stepCompleted("transfer", "private-instance", "reserve");
                listener.stepCompleted("transfer", "another-instance", "reserve");
                listener.compensated("transfer", "private-instance", "reserve");
                listener.inDoubt("transfer", "private-instance", "reserve");
                for (SagaStatus status : SagaStatus.values()) {
                  listener.finished(
                      "transfer",
                      "private-instance",
                      status,
                      new IllegalStateException("private-failure"));
                  assertEquals(
                      1,
                      meters
                          .get("axiz.saga.finished")
                          .tag("status", status.name().toLowerCase(Locale.ROOT))
                          .counter()
                          .count());
                }
                assertEquals(
                    2,
                    meters
                        .get("axiz.saga.steps")
                        .tag("saga", "transfer")
                        .tag("step", "reserve")
                        .counter()
                        .count());
                assertEquals(1, meters.get("axiz.saga.compensations").counter().count());
                assertEquals(1, meters.get("axiz.saga.in_doubt").counter().count());
                meters
                    .getMeters()
                    .forEach(
                        meter ->
                            assertThat(meter.getId().getTags().toString())
                                .doesNotContain("private", "another-instance"));
              });
    } finally {
      meters.close();
    }
  }

  record Transfer(String reference, BigDecimal amount) {}

  @Test
  void persistedContextPreservesUnicodeAndDecimalScaleAndRejectsMalformedState() {
    var codec = new JacksonContextCodec<>(JsonMapper.builder().build(), Transfer.class);
    var expected = new Transfer("transferencia-ñ", new BigDecimal("123456789.1200"));
    assertEquals(expected, codec.decode(codec.encode(expected)));
    assertEquals(
        expected, codec.decode("{\"reference\":\"transferencia-ñ\",\"amount\":123456789.1200}"));
    assertThrows(JacksonException.class, () -> codec.decode("{broken"));
    assertThrows(JacksonException.class, () -> codec.decode("{\"amount\":\"not-a-number\"}"));
  }
}
