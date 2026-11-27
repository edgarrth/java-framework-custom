package pe.axiz.core.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class FailuresTest {

  @Test
  void typesAndOriginsLocateTheCauseWithoutAnyMessage() {
    var driver = new SQLException("Key (email)=(ana@example.com) already exists", "23505");
    driver.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("org.postgresql.core.v3.QueryExecutorImpl", "receive", "Q.java", 1)
        });
    var failure = new IllegalStateException("card 4111111111111111", driver);
    String text = Failures.describe(failure);
    assertTrue(
        text.startsWith(
            "java.lang.IllegalStateException at pe.axiz.core.errors.FailuresTest."
                + "typesAndOriginsLocateTheCauseWithoutAnyMessage(FailuresTest.java:"),
        text);
    assertTrue(
        text.endsWith(
            " | caused by java.sql.SQLException at"
                + " org.postgresql.core.v3.QueryExecutorImpl.receive(Q.java:1)"),
        text);
    assertFalse(text.contains("ana@example.com"), text);
    assertFalse(text.contains("4111"), text);
  }

  @Test
  void failuresThrownInsideTheJdkAlsoNameTheirCaller() {
    try {
      Objects.requireNonNull(null, "secret-value");
    } catch (NullPointerException e) {
      String text = Failures.describe(e);
      assertTrue(
          text.startsWith("java.lang.NullPointerException at java.base/java.util.Objects."), text);
      assertTrue(text.contains(" via pe.axiz.core.errors.FailuresTest."), text);
      assertFalse(text.contains("secret-value"), text);
    }
  }

  @Test
  void chainsAreBoundedAndCyclesTerminate() {
    var a = new IllegalStateException("a");
    var b = new IllegalStateException("b", a);
    a.initCause(b);
    String cyclic = Failures.describe(b);
    assertEquals(1, cyclic.split(" \\| caused by ").length - 1, cyclic);

    Throwable deep = new IllegalStateException("0");
    for (int i = 1; i < 20; i++) deep = new IllegalStateException(Integer.toString(i), deep);
    assertTrue(Failures.describe(deep).endsWith(" | caused by ..."));
    assertEquals("none", Failures.describe(null));
    var bare = new IllegalStateException();
    bare.setStackTrace(new StackTraceElement[0]);
    assertEquals("java.lang.IllegalStateException", Failures.describe(bare));
  }

  @Test
  void rootCauseIsTheInnermostFailure() {
    var root = new SQLException("x");
    assertSame(root, Failures.rootCause(new IllegalStateException(new RuntimeException(root))));
    var alone = new IllegalStateException();
    assertSame(alone, Failures.rootCause(alone));
  }
}
