package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.PayloadException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Additive v1 event evolution is independent of the consumer's framework version. */
class EventPayloadCompatibilityTest {
  record StructuredPayload(Map<String, Object> details, List<Object> entries) {}

  @Test
  void untypedNestedEventNumbersPreserveDecimalPrecisionWithoutChangingTheHttpMapper() {
    JsonMapper http = JsonMapper.builder().build();
    var codec = new JacksonPayloadCodec(http);
    String decimal = "0.123456789012345678901234567890";
    byte[] bytes =
        ("{\"details\":{\"amount\":" + decimal + "},\"entries\":[{\"amount\":" + decimal + "}]}")
            .getBytes(StandardCharsets.UTF_8);
    StructuredPayload payload = codec.decode(bytes, StructuredPayload.class);
    assertEquals(new BigDecimal(decimal), payload.details().get("amount"));
    assertEquals(new BigDecimal(decimal), ((Map<?, ?>) payload.entries().getFirst()).get("amount"));
    assertEquals(payload, codec.decode(codec.encode(payload), StructuredPayload.class));
    assertEquals(
        Double.class,
        http.readValue(bytes, StructuredPayload.class).details().get("amount").getClass());
  }

  record V1(String paymentId) {
    V1 {
      Objects.requireNonNull(paymentId);
    }
  }

  record V2(String paymentId, @Nullable String channel) {
    V2 {
      Objects.requireNonNull(paymentId);
    }
  }

  @Test
  void newProducerAndOldConsumerCoexistWithoutWeakeningTheHttpMapper() {
    JsonMapper http =
        JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    var codec = new JacksonPayloadCodec(http);
    byte[] newEvent = codec.encode(new V2("payment-1", "mobile"));
    assertEquals(new V1("payment-1"), codec.decode(newEvent, V1.class));
    assertThrows(JacksonException.class, () -> http.readValue(newEvent, V1.class));
  }

  @Test
  void oldProducerAndNewConsumerAcceptAnAbsentOptionalFieldButRejectBreakingChanges() {
    var codec = new JacksonPayloadCodec(JsonMapper.builder().build());
    V2 decoded = codec.decode(codec.encode(new V1("payment-1")), V2.class);
    assertEquals("payment-1", decoded.paymentId());
    assertNull(decoded.channel());
    assertThrows(
        PayloadException.class,
        () ->
            codec.decode(
                "{\"renamedId\":\"payment-1\"}".getBytes(StandardCharsets.UTF_8), V1.class));
  }
}
