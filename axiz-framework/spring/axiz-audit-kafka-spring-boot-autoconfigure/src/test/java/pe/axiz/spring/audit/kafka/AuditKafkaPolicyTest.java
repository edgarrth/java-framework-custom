package pe.axiz.spring.audit.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuditKafkaPolicyTest {
  @Test
  void destinationsAndDeadlinesMustBeExplicitAndBounded() {
    for (String topic : new String[] {"", " ", "${AUDIT_TOPIC}"}) {
      assertThatThrownBy(() -> new AxizAuditKafkaProperties(topic, Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofSeconds(61)}) {
      assertThatThrownBy(() -> new AxizAuditKafkaProperties("audit", timeout))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new AxizAuditKafkaProperties(null, Duration.ofMillis(1)).topic()).isNull();
    assertThat(new AxizAuditKafkaProperties("audit", Duration.ofMinutes(1)).timeout())
        .isEqualTo(Duration.ofMinutes(1));
  }
}
