package pe.axiz.core.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudEventJsonTest {

  @Test
  void jsonDataIsEmbedded() {
    EventEnvelope e =
        new EventEnvelope(
            "1",
            "urn:s",
            "t.v1",
            Instant.parse("2026-01-01T00:00:00Z"),
            "agg",
            "application/json",
            "{\"amount\":\"10.00\",\"n\":3}".getBytes(StandardCharsets.UTF_8),
            Map.of("correlationid", "c-1"));
    String json = CloudEventJson.write(e);
    assertTrue(json.contains("\"data\":{\"amount\":\"10.00\",\"n\":3}"), json);
    EventEnvelope back = CloudEventJson.read(json);
    assertEquals(e, back);
    assertEquals("agg", back.subject());
    assertEquals("c-1", back.correlationId().orElseThrow());
    assertEquals("{\"amount\":\"10.00\",\"n\":3}", new String(back.data(), StandardCharsets.UTF_8));
  }

  @Test
  void binaryDataUsesBase64() {
    byte[] data = {1, 2, 3};
    EventEnvelope e =
        new EventEnvelope(
            "2", "urn:s", "t", Instant.EPOCH, null, "application/octet-stream", data, Map.of());
    String json = CloudEventJson.write(e);
    assertTrue(json.contains("data_base64"));
    assertArrayEquals(data, CloudEventJson.read(json).data());
  }

  @Test
  void rejectsInvalidDocuments() {
    assertThrows(PayloadException.class, () -> CloudEventJson.read("not json"));
    assertThrows(PayloadException.class, () -> CloudEventJson.read("{\"id\":\"1\"}"));
    assertThrows(
        PayloadException.class,
        () -> CloudEventJson.read("{\"id\":\"1\",\"source\":\"s\",\"type\":\"t\",\"time\":\"x\"}"));
    EventEnvelope empty =
        CloudEventJson.read("{\"id\":\"1\",\"source\":\"s\",\"type\":\"t\",\"n\":5}");
    assertEquals(0, empty.data().length);
    assertEquals("5", empty.extension("n").orElseThrow());
  }
}
