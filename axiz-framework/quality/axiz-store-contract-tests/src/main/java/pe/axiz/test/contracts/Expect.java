package pe.axiz.test.contracts;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Minimal assertions so the contracts do not depend on a test library. */
final class Expect {

  private Expect() {}

  static void isTrue(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  static void isFalse(boolean condition, String message) {
    isTrue(!condition, message);
  }

  static void equal(@Nullable Object expected, @Nullable Object actual, String message) {
    if (!Objects.equals(expected, actual)) {
      throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
    }
  }
}
