package pe.axiz.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Dos listas de argumentos distintas nunca comparten una clave; las iguales siempre la comparten.
 */
class CacheKeysTest {

  enum Channel {
    WEB,
    MOBILE
  }

  enum Other {
    WEB
  }

  record Query(String customer, int page, List<String> filters) {}

  record Wrapper(Object value) {}

  static final class Account implements CacheKeyPart {
    private final String number;

    Account(String number) {
      this.number = number;
    }

    @Override
    public String cacheKey() {
      return number;
    }
  }

  static final class Opaque {}

  @Test
  void nullIsNotTheUnderscoreNorTheDashString() {
    assertNotEquals(CacheKeys.of((Object) null), CacheKeys.of("_"));
    assertNotEquals(CacheKeys.of((Object) null), CacheKeys.of("-"));
    assertNotEquals(CacheKeys.of((Object) null), CacheKeys.of(""));
    assertEquals("-", CacheKeys.of((Object[]) null));
  }

  @Test
  void separatorsCannotMoveTheBoundaryBetweenArguments() {
    assertNotEquals(CacheKeys.of("a:b", "c"), CacheKeys.of("a", "b:c"));
    assertNotEquals(CacheKeys.of("a\\\\", "b"), CacheKeys.of("a", "\\\\b"));
    assertNotEquals(CacheKeys.of(List.of("a,b")), CacheKeys.of(List.of("a", "b")));
    assertNotEquals(CacheKeys.of(List.of("a]", "b")), CacheKeys.of(List.of("a"), "b"));
    assertNotEquals(
        CacheKeys.of(Map.of("k=v", "x")), CacheKeys.of(Map.of("k", "v=x")), "map entry boundary");
  }

  @Test
  void theTypeIsPartOfTheKey() {
    assertNotEquals(CacheKeys.of("7"), CacheKeys.of(7));
    assertNotEquals(CacheKeys.of(7), CacheKeys.of(7L));
    assertNotEquals(CacheKeys.of((short) 7), CacheKeys.of((byte) 7));
    assertNotEquals(CacheKeys.of(1.0d), CacheKeys.of(1.0f));
    assertNotEquals(CacheKeys.of(new BigDecimal("1")), CacheKeys.of(BigInteger.ONE));
    assertNotEquals(CacheKeys.of('a'), CacheKeys.of("a"));
    assertNotEquals(CacheKeys.of(true), CacheKeys.of("true"));
    assertNotEquals(CacheKeys.of(Channel.WEB), CacheKeys.of(Other.WEB));
    assertNotEquals(CacheKeys.of(Channel.WEB), CacheKeys.of("WEB"));
    assertNotEquals(CacheKeys.of(List.of("a")), CacheKeys.of(Set.of("a")));
    assertNotEquals(CacheKeys.of(new StringBuilder("a")), CacheKeys.of('a'));
  }

  @Test
  void arraysKeepTheirComponentType() {
    // un método que recibe Object recibió int[]{7} y luego Integer[]{7}
    assertNotEquals(CacheKeys.of((Object) new int[] {7}), CacheKeys.of((Object) new Integer[] {7}));
    assertNotEquals(CacheKeys.of((Object) new int[] {7}), CacheKeys.of((Object) new long[] {7}));
    assertNotEquals(
        CacheKeys.of((Object) new Integer[] {7}), CacheKeys.of((Object) new Object[] {7}));
    assertNotEquals(
        CacheKeys.of((Object) new char[] {'a'}), CacheKeys.of((Object) new String[] {"a"}));
    assertNotEquals(
        CacheKeys.of((Object) new int[0]), CacheKeys.of((Object) new Integer[0]), "empty arrays");
    assertNotEquals(CacheKeys.of((Object) new int[0]), CacheKeys.of(List.of()));
    assertNotEquals(
        CacheKeys.of((Object) new int[][] {{7}}),
        CacheKeys.of((Object) new Integer[][] {{7}}),
        "multidimensional");
    assertNotEquals(CacheKeys.of((Object) new int[][] {{7}}), CacheKeys.of((Object) new int[] {7}));
    assertEquals(
        CacheKeys.of((Object) new int[][] {{1, 2}, {3}}),
        CacheKeys.of((Object) new int[][] {{1, 2}, {3}}));
    assertEquals(
        "a([I)=[i\\=7]", CacheKeys.part(new int[] {7}), "format: array class, then the elements");
  }

  @Test
  void otherCollectionsKeepTheirClass() {
    assertNotEquals(CacheKeys.of(new ArrayDeque<>(List.of(1))), CacheKeys.of(List.of(1)));
    assertEquals(
        CacheKeys.of(new ArrayDeque<>(List.of(1, 2))),
        CacheKeys.of(new ArrayDeque<>(List.of(1, 2))));
  }

  @Test
  void bigDecimalKeepsItsScaleLikeEquals() {
    assertNotEquals(CacheKeys.of(new BigDecimal("1.0")), CacheKeys.of(new BigDecimal("1.00")));
    assertEquals(CacheKeys.of(new BigDecimal("1.00")), CacheKeys.of(new BigDecimal("1.00")));
  }

  @Test
  void equalValuesShareTheKeyWhateverTheirOrderInSetsAndMaps() {
    Set<String> a = new LinkedHashSet<>(List.of("x", "y", "z"));
    Set<String> b = new HashSet<>(List.of("z", "y", "x"));
    assertEquals(CacheKeys.of(a), CacheKeys.of(b));
    assertEquals(CacheKeys.of(a), CacheKeys.of(new LinkedHashSet<>(List.of("z", "y", "x"))));
    assertNotEquals(CacheKeys.of(a), CacheKeys.of(Set.of("x", "y")));
    Map<String, Integer> m1 = new LinkedHashMap<>();
    m1.put("b", 2);
    m1.put("a", 1);
    assertEquals(CacheKeys.of(m1), CacheKeys.of(new TreeMap<>(m1)));
    assertNotEquals(CacheKeys.of(List.of(1, 2)), CacheKeys.of(List.of(2, 1)), "lists keep order");
  }

  @Test
  void supportedValueTypes() {
    UUID id = UUID.fromString("0198f2a4-2b5e-7c1a-9d3e-4f5a6b7c8d9e");
    assertEquals("u=" + id, CacheKeys.part(id));
    assertEquals("C=PEN", CacheKeys.part(Currency.getInstance("PEN")));
    assertEquals("L=es-PE", CacheKeys.part(Locale.forLanguageTag("es-PE")));
    assertEquals("t(java.time.LocalDate)=2026-10-18", CacheKeys.part(LocalDate.of(2026, 10, 18)));
    assertTrue(CacheKeys.part(Instant.EPOCH).startsWith("t(java.time.Instant)="));
    assertEquals("B=AQI", CacheKeys.part(new byte[] {1, 2}));
    assertNotEquals(CacheKeys.of(new byte[] {1, 2}), CacheKeys.of(new byte[] {1, 3}));
    assertEquals("o=[]", CacheKeys.part(Optional.empty()));
    assertNotEquals(CacheKeys.of(Optional.empty()), CacheKeys.of(Optional.of("")));
    assertEquals(CacheKeys.of(new int[] {1, 2}), CacheKeys.of(new int[] {1, 2}));
    assertNotEquals(CacheKeys.of(new int[] {1, 2}), CacheKeys.of(List.of(1, 2)));
    assertEquals(
        CacheKeys.of((Object) new String[] {"a", null}),
        CacheKeys.of((Object) new String[] {"a", null}));
    assertEquals(CacheKeys.of(Arrays.asList("a", null)), CacheKeys.of(Arrays.asList("a", null)));
  }

  @Test
  void recordsAreKeyedByTheirComponents() {
    Query q1 = new Query("ana", 1, List.of("open"));
    assertEquals(CacheKeys.of(q1), CacheKeys.of(new Query("ana", 1, List.of("open"))));
    assertNotEquals(CacheKeys.of(q1), CacheKeys.of(new Query("ana", 2, List.of("open"))));
    assertNotEquals(CacheKeys.of(q1), CacheKeys.of(new Query("ana", 1, List.of("open", "x"))));
    assertNotEquals(CacheKeys.of(new Wrapper("1")), CacheKeys.of(new Wrapper(1)));
  }

  @Test
  void valueObjectsCanNameTheirOwnKey() {
    assertEquals(CacheKeys.of(new Account("001")), CacheKeys.of(new Account("001")));
    assertNotEquals(CacheKeys.of(new Account("001")), CacheKeys.of("001"));
    assertTrue(CacheKeys.of(new Account("0:1")).contains("0\\:1"));
  }

  @Test
  void anArbitraryObjectHasNoStableKey() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> CacheKeys.of(new Opaque()));
    assertTrue(e.getMessage().contains(Opaque.class.getName()), e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> CacheKeys.of(List.of(new Opaque())));
  }
}
