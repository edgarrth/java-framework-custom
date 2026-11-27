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
        Problem.builder()
            .type("about:blank")
            .title("Invalid request")
            .status(400)
            .detail("La solicitud no es válida.")
            .instance("/v1/orders")
            .code("AXZ-CORE-0001")
            .correlationId("c-1")
            .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
            .violations(List.of(new FieldViolation("amount", "Positive", "must be positive", "-1")))
            .external(Map.of("system", "core"))
            .build();
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
        Problem.builder()
            .type("about:blank")
            .title("t")
            .status(500)
            .detail("d")
            .instance(null)
            .code("AXZ-CORE-0500")
            .correlationId(null)
            .timestamp(Instant.EPOCH)
            .violations(List.of())
            .external(null)
            .build();
    assertEquals(
        "{\"type\":\"about:blank\",\"title\":\"t\",\"status\":500,\"detail\":\"d\","
            + "\"code\":\"AXZ-CORE-0500\",\"timestamp\":\"1970-01-01T00:00:00Z\"}",
        ProblemJson.write(minimal));
  }
}
