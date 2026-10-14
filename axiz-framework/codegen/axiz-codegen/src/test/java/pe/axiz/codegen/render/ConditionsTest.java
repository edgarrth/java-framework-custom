package pe.axiz.codegen.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionsTest {

  private final Map<String, Object> ctx =
      Map.of(
          "fw",
          Map.of("cqrs", true, "bff", false),
          "items",
          List.of(1),
          "empty",
          List.of(),
          "name",
          "x");

  @Test
  void evaluatesPathsAndOperators() {
    assertTrue(Conditions.test(null, ctx));
    assertTrue(Conditions.test("fw.cqrs", ctx));
    assertFalse(Conditions.test("fw.bff", ctx));
    assertTrue(Conditions.test("!fw.bff", ctx));
    assertTrue(Conditions.test("fw.bff || fw.cqrs", ctx));
    assertFalse(Conditions.test("fw.bff && fw.cqrs", ctx));
    assertTrue(Conditions.test("items && name", ctx));
    assertFalse(Conditions.test("empty", ctx));
    assertFalse(Conditions.test("missing.path", ctx));
  }
}
