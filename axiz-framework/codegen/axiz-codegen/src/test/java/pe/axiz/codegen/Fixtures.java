package pe.axiz.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;

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
      Framework framework,
      Stack stack,
      String persistence,
      List<String> capabilities) {
    String name = "orders-" + archetype;
    boolean rest = !List.of("event-processor", "batch", "library", "starter").contains(archetype);
    return new ProjectRequest(
        name,
        "pe.axiz.orders",
        "pe.axiz.orders." + name.replace("-", ""),
        "Orders of the example domain",
        archetype,
        framework,
        stack,
        persistence,
        capabilities,
        rest ? orders() : null,
        null,
        "1.0.0");
  }
}
