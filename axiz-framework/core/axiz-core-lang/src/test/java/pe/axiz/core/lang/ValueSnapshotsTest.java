package pe.axiz.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValueSnapshotsTest {
  private static final class MutableDecimal extends BigDecimal {
    private static final long serialVersionUID = 1L;

    MutableDecimal() {
      super("1");
    }
  }

  @Test
  void preservesTopLevelNullAndRejectsSubclassedNumbers() {
    var source = new HashMap<String, Object>();
    source.put("absent", null);
    var copy = ValueSnapshots.map(source);
    source.clear();
    assertEquals(1, copy.size());
    assertNull(copy.get("absent"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueSnapshots.map(Map.of("value", new MutableDecimal())));
  }

  @Test
  void nestedObjectsArraysAndNullsAreDetachedAndReadOnly() {
    var nested = new HashMap<String, Object>();
    nested.put("value", "before");
    var values = new ArrayList<Object>();
    values.add(nested);
    values.add(null);
    var frozen = ValueSnapshots.map(Map.of("values", values));
    nested.put("value", "after");
    values.clear();
    var copy = (List<?>) frozen.get("values");
    assertEquals(Map.of("value", "before"), copy.getFirst());
    assertNull(copy.get(1));
    assertThrows(UnsupportedOperationException.class, copy::clear);
    assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) copy.getFirst()).clear());
    assertThrows(UnsupportedOperationException.class, frozen::clear);
  }

  @Test
  void snapshotsScalarTypesAndRejectsCyclesAndNonValueValues() {
    for (Object scalar :
        List.of("text", true, (byte) 1, (short) 2, 3, 4L, 5F, 6D, BigDecimal.ONE, BigInteger.ONE)) {
      assertEquals(scalar, ValueSnapshots.map(Map.of("value", scalar)).get("value"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueSnapshots.map(Map.of("value", new StringBuilder())));
    for (Object invalidNumber : List.of(Float.NaN, Double.POSITIVE_INFINITY))
      assertThrows(
          IllegalArgumentException.class, () -> ValueSnapshots.map(Map.of("value", invalidNumber)));
    var invalid = new HashMap<Object, Object>();
    invalid.put(1, "invalid key");
    assertThrows(
        IllegalArgumentException.class, () -> ValueSnapshots.map(Map.of("value", invalid)));
    var cyclic = new HashMap<String, Object>();
    cyclic.put("self", cyclic);
    assertThrows(IllegalArgumentException.class, () -> ValueSnapshots.map(cyclic));
  }
}
