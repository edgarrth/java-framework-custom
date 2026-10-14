package pe.axiz.core.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProblemJsonTest {

  @Test
  void writesStandardAndExtensionMembers() {
    Problem p =
        new Problem(
            "about:blank",
            "Invalid request",
            400,
            "La solicitud no es válida.",
            "/v1/orders",
            "AXZ-CORE-0001",
            "c-1",
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(new FieldViolation("amount", "Positive", "must be positive", "-1")),
            Map.of("system", "core"));
    String json = ProblemJson.write(p);
    assertTrue(
        json.startsWith("{\"type\":\"about:blank\",\"title\":\"Invalid request\",\"status\":400"),
        json);
    assertTrue(
        json.contains(
            "\"violations\":[{\"field\":\"amount\",\"code\":\"Positive\",\"message\":\"must be"
                + " positive\",\"rejectedValue\":\"-1\"}]"));
    assertTrue(json.contains("\"external\":{\"system\":\"core\"}"));
    Problem minimal =
        new Problem(
            "about:blank",
            "t",
            500,
            "d",
            null,
            "AXZ-CORE-0500",
            null,
            Instant.EPOCH,
            List.of(),
            null);
    assertEquals(
        "{\"type\":\"about:blank\",\"title\":\"t\",\"status\":500,\"detail\":\"d\","
            + "\"code\":\"AXZ-CORE-0500\",\"timestamp\":\"1970-01-01T00:00:00Z\"}",
        ProblemJson.write(minimal));
  }
}
