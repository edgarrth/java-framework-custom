import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

/** CI fixture generated through the framework's public API, including real AsyncAPI payloads. */
class GenerateReactiveNativeSample {
  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    var request = new ProjectRequest(
        "sample-native-orders", "pe.axiz", "pe.axiz.samples.nativeorders",
        "Reactive native framework acceptance", "business", Structure.HEXAGONAL,
        Stack.REACTIVE, "aurora", List.of("messaging-sqs"),
        Files.readString(Path.of(args[1])), Files.readString(Path.of(args[2])), args[3]);
    ProjectGenerator.withDefaults().generate(request).writeTo(output, false);
    Path test = output.resolve(
        "src/test/java/pe/axiz/samples/nativeorders/GeneratedEventContractTest.java");
    Files.writeString(test, """
        package pe.axiz.samples.nativeorders;

        import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
        import java.math.BigDecimal;
        import java.nio.charset.StandardCharsets;
        import java.time.Instant;
        import java.util.Map;
        import org.junit.jupiter.api.Test;
        import pe.axiz.core.messaging.EventEnvelope;
        import pe.axiz.spring.messaging.JacksonPayloadCodec;
        import pe.axiz.samples.nativeorders.adapter.in.messaging.OrderCreatedV1Handler;
        import pe.axiz.samples.nativeorders.domain.event.OrderCreatedV1;
        import tools.jackson.databind.json.JsonMapper;

        class GeneratedEventContractTest {
          @Test
          void preservesDecimalsAndRegistersTheActualContractWithoutAcknowledgingAStub() throws Exception {
            var codec = new JacksonPayloadCodec(JsonMapper.builder().build());
            String amount = "0.123456789012345678901234567890";
            byte[] bytes = ("{\\"id\\":\\"order-1\\",\\"lines\\":[{\\"amount\\":"
                + amount + "}],\\"futureOptionalField\\":true}").getBytes(StandardCharsets.UTF_8);
            var payload = codec.decode(bytes, OrderCreatedV1.class);
            assertEquals(new BigDecimal(amount), payload.lines().getFirst().get("amount"));
            assertEquals(payload, codec.decode(codec.encode(payload), OrderCreatedV1.class));
            var handler = new OrderCreatedV1Handler();
            assertEquals("pe.axiz.order.created.v1", handler.eventType());
            assertEquals(OrderCreatedV1.class, handler.payloadType());
            var event = new EventEnvelope("event-1", "urn:axiz:acceptance", handler.eventType(),
                Instant.EPOCH, null, "application/json", bytes, Map.of());
            assertThrows(UnsupportedOperationException.class, () -> handler.handle(payload, event));
            assertEquals(0, OrderCreatedV1.class.getAnnotations().length);
            assertNotNull(Class.forName("pe.axiz.samples.nativeorders.config.OrderCreatedV1PayloadConfiguration").getAnnotation(
                org.springframework.aot.hint.annotation.RegisterReflectionForBinding.class));
          }
        }
        """);
  }
}
