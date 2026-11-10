package pe.axiz.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResultTest {

  @Test
  void okMapsAndFolds() {
    Result<Integer, String> result = Result.ok(2);
    assertTrue(result.isOk());
    assertFalse(result.isErr());
    assertEquals(Optional.of(4), result.map(v -> v * 2).success());
    assertEquals("v=2", result.fold(v -> "v=" + v, e -> "e=" + e));
    assertEquals(2, result.orElse(0));
    assertEquals(Optional.empty(), result.failure());
  }

  @Test
  void errShortCircuitsMapAndFlatMap() {
    Result<Integer, String> result = Result.err("insufficient-funds");
    assertTrue(result.isErr());
    assertEquals(Optional.of("insufficient-funds"), result.map(v -> v * 2).failure());
    assertEquals(
        Optional.of("insufficient-funds"), result.flatMap(v -> Result.ok(v + 1)).failure());
    assertEquals(Optional.of(18), result.mapError(String::length).failure());
    assertEquals(-1, result.orElse(-1));
    assertEquals(19, result.orElseGet(e -> e.length() + 1));
  }

  @Test
  void flatMapChainsSuccesses() {
    Result<Integer, String> result = Result.<Integer, String>ok(1).flatMap(v -> Result.ok(v + 1));
    assertEquals(Optional.of(2), result.success());
    Result<Integer, String> failed = Result.<Integer, String>ok(1).flatMap(v -> Result.err("no"));
    assertEquals(Optional.of("no"), failed.failure());
  }

  @Test
  void orElseThrowRaisesMappedException() {
    Result<Integer, String> result = Result.err("bad");
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> result.orElseThrow(IllegalStateException::new));
    assertEquals("bad", ex.getMessage());
  }

  @Test
  void catchingConvertsExceptions() {
    Result<Integer, String> result =
        Result.catching(
            () -> {
              throw new IllegalArgumentException("boom");
            },
            Throwable::getMessage);
    assertEquals(Optional.of("boom"), result.failure());
    assertEquals(
        Optional.of(5), Result.<Integer, String>catching(() -> 5, Throwable::getMessage).success());
  }

  @Test
  void peekRunsOnlyOnMatchingBranch() {
    List<String> seen = new ArrayList<>();
    Result.<String, String>ok("a").peek(seen::add).peekError(e -> seen.add("err:" + e));
    Result.<String, String>err("b").peek(seen::add).peekError(e -> seen.add("err:" + e));
    assertEquals(List.of("a", "err:b"), seen);
  }

  @Test
  void rejectsNulls() {
    assertThrows(NullPointerException.class, () -> new Result.Ok<String, String>(null));
    assertThrows(NullPointerException.class, () -> new Result.Err<String, String>(null));
  }
}
