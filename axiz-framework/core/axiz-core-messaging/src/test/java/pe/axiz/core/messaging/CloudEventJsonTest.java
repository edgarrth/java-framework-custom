package pe.axiz.core.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudEventJsonTest {
  @Test
  void structuredStringPayloadUsesItsMediaTypeAndPreservesEnvelopeAttributes() {
    for (String mediaType : List.of("application/json", "application/problem+json", "text/plain")) {
      var event =
          CloudEventJson.read(
              "{\"id\":\"1\",\"source\":\"urn:s\",\"type\":\"t\","
                  + "\"datacontenttype\":\""
                  + mediaType
                  + "\",\"data\":\"hello\"}");
      assertEquals(mediaType, event.dataContentType());
      assertEquals(
          mediaType.equals("text/plain") ? "hello" : "\"hello\"",
          new String(event.data(), StandardCharsets.UTF_8));
    }
    var event =
        CloudEventJson.read(
            "{\"id\":\"1\",\"source\":\"urn:s\",\"type\":\"t\",\"data\":{\"n\":7}}");
    assertEquals("{\"n\":7}", new String(event.data(), StandardCharsets.UTF_8));
  }

  @Test
  void envelopeCanRemoveUntrustedExtensionsWithoutLosingPayloadOrCorrelation() {
    var event =
        EventEnvelope.builder()
            .id("id")
            .source("urn:s")
            .type("t")
            .time(Instant.EPOCH)
            .subject(null)
            .dataContentType("text/plain")
            .data(new byte[] {17})
            .extensions(Map.of("untrusted", "remove", "correlationid", "keep"))
            .build();
    var cleaned = event.withoutExtensions("untrusted");
    assertTrue(cleaned.extension("untrusted").isEmpty());
    assertEquals("keep", cleaned.correlationId().orElseThrow());
    assertArrayEquals(new byte[] {17}, cleaned.data());
    assertEquals(event, CloudEventJson.read(CloudEventJson.write(event)));
    assertEquals(event.hashCode(), CloudEventJson.read(CloudEventJson.write(event)).hashCode());
    assertEquals(event, cleaned, "CloudEvents identity is source plus id");
    assertNotEquals(
        event,
        EventEnvelope.builder()
            .id("different")
            .source("urn:s")
            .type("t")
            .time(Instant.EPOCH)
            .subject(null)
            .dataContentType("text/plain")
            .data(new byte[] {17})
            .extensions(Map.of())
            .build());
    assertEquals(cleaned, cleaned.withoutExtensions("absent"));
  }

  @Test
  void signedJsonWhitespaceExponentsAndNegativeZeroAreNotRewritten() {
    byte[] data = "{\"x\": -0.0, \"y\": 1.0E100, \"z\": 10.50 }".getBytes(StandardCharsets.UTF_8);
    var event =
        EventEnvelope.builder()
            .id("signed")
            .source("urn:test")
            .type("signed.v1")
            .time(Instant.EPOCH)
            .subject(null)
            .dataContentType("application/json")
            .data(data)
            .extensions(Map.of())
            .build();
    assertArrayEquals(data, CloudEventJson.read(CloudEventJson.write(event)).data());
  }

  @Test
  void encodedJsonDataPreservesItsExactBytes() {
    EventEnvelope e =
        EventEnvelope.builder()
            .id("1")
            .source("urn:s")
            .type("t.v1")
            .time(Instant.parse("2026-01-01T00:00:00Z"))
            .subject("agg")
            .dataContentType("application/json")
            .data("{\"amount\":\"10.00\",\"n\":3}".getBytes(StandardCharsets.UTF_8))
            .extensions(Map.of("correlationid", "c-1"))
            .build();
    String json = CloudEventJson.write(e);
    assertTrue(json.contains("\"data_base64\":"), json);
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
        EventEnvelope.builder()
            .id("2")
            .source("urn:s")
            .type("t")
            .time(Instant.EPOCH)
            .subject(null)
            .dataContentType("application/octet-stream")
            .data(data)
            .extensions(Map.of())
            .build();
    String json = CloudEventJson.write(e);
    assertTrue(json.contains("data_base64"));
    assertArrayEquals(data, CloudEventJson.read(json).data());
  }

  @Test
  void rejectsInvalidDocuments() {
    assertThrows(
        PayloadException.class,
        () ->
            CloudEventJson.read(
                "{\"specversion\":\"1.0\",\"id\":\"1\",\"source\":\"s\",\"type\":\"t\","
                    + "\"data\":null,\"data_base64\":\"\"}"));
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
