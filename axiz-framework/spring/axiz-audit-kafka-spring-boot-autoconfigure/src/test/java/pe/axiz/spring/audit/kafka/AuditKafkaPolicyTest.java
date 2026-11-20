package pe.axiz.spring.audit.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditKafkaPolicyTest {
  @Test
  void destinationsAndDeadlinesMustBeExplicitAndBounded() {
    for (String topic : new String[] {"", " ", "${AUDIT_TOPIC}"}) {
      assertThatThrownBy(() -> new AxizAuditKafkaProperties(topic, Duration.ofSeconds(1), 2, 256))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofSeconds(61)}) {
      assertThatThrownBy(() -> new AxizAuditKafkaProperties("audit", timeout, 2, 256))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new AxizAuditKafkaProperties(null, Duration.ofMillis(1), 2, 256).topic()).isNull();
    assertThat(new AxizAuditKafkaProperties("audit", Duration.ofMinutes(1), 2, 256).timeout())
        .isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void validatesDispatcherResourcesBeforeCreatingThreads() {
    for (int workers : List.of(0, 65))
      assertThatThrownBy(
              () -> new AxizAuditKafkaProperties("audit", Duration.ofSeconds(1), workers, 1))
          .isInstanceOf(IllegalArgumentException.class);
    for (int queued : List.of(-1, 65537))
      assertThatThrownBy(
              () -> new AxizAuditKafkaProperties("audit", Duration.ofSeconds(1), 1, queued))
          .isInstanceOf(IllegalArgumentException.class);
    assertThat(new AxizAuditKafkaProperties("audit", Duration.ofSeconds(1), 64, 0).sendWorkers())
        .isEqualTo(64);
    assertThat(
            new AxizAuditKafkaProperties("audit", Duration.ofSeconds(1), 1, 65536)
                .sendQueueCapacity())
        .isEqualTo(65536);
  }
}
