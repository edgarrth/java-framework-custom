package pe.axiz.spring.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class MskAuthenticationTest {
  @Test
  void rejectsKnownIamListenersOnlyWhenIamIsDisabled() {
    for (String host :
        List.of(
            "b-1.orders.id.c2.kafka.us-east-1.amazonaws.com",
            "boot-id.c2.kafka-serverless.us-gov-west-1.amazonaws.com",
            "b-2.id.kafka.cn-north-1.amazonaws.com.cn")) {
      for (int port : List.of(9098, 9198, 20098)) {
        String servers = "localhost:9092, " + host + ":" + port;
        assertThrows(
            IllegalArgumentException.class, () -> MskAuthentication.validate(servers, false));
        assertDoesNotThrow(() -> MskAuthentication.validate(servers, true));
      }
      assertDoesNotThrow(() -> MskAuthentication.validate(host + ":9094", false));
    }
  }

  @Test
  void leavesGenericKafkaAndOtherListenersAlone() {
    for (String servers :
        List.of(
            "localhost:9098",
            "broker.internal:9198",
            "[::1]:20098",
            "kafka.us-east-1.amazonaws.com.evil.test:9098",
            "b-1.kafka.us-east-1.amazonaws.com:19098")) {
      assertDoesNotThrow(() -> MskAuthentication.validate(servers, false));
    }
  }
}
