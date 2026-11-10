package pe.axiz.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import pe.axiz.codegen.model.Extension;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

/** Shared test data. */
public final class Fixtures {

  private Fixtures() {}

  public static String orders() {
    try (InputStream in = Fixtures.class.getResourceAsStream("/contracts/orders.yaml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static ProjectRequest request(
      String archetype,
      Structure structure,
      Stack stack,
      String persistence,
      List<String> capabilities,
      Extension... extensions) {
    String name =
        switch (archetype) {
          case "business" -> "orders";
          case "event-processor" -> "orders-processor";
          default -> "orders-" + archetype;
        };
    boolean rest = !List.of("event-processor", "batch", "library", "starter").contains(archetype);
    return new ProjectRequest(
        name,
        "pe.axiz.orders",
        "pe.axiz.orders." + name.replace("-", ""),
        "Orders of the example domain",
        archetype,
        structure,
        stack,
        persistence,
        capabilities,
        rest ? orders() : null,
        null,
        "2.0.0",
        List.of(extensions));
  }
}
