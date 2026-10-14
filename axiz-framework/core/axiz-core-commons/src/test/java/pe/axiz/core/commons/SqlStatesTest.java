package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.jdbc.SqlStates;

class SqlStatesTest {

  @Test
  void detectsDuplicateKeys() {
    assertTrue(SqlStates.isDuplicateKey(new SQLException("dup", "23505")));
    SQLException chained = new SQLException("batch", "XX000");
    chained.setNextException(new SQLException("unique constraint", "23000"));
    assertTrue(SqlStates.isDuplicateKey(chained));
    assertFalse(SqlStates.isDuplicateKey(new SQLException("fk", "23503")));
    assertFalse(SqlStates.isDuplicateKey(new SQLException("x", (String) null)));
  }

  @Test
  void validatesIdentifiers() {
    assertEquals("axiz_outbox", SqlStates.identifier("axiz_outbox"));
    assertThrows(IllegalArgumentException.class, () -> SqlStates.identifier("x; drop table y"));
    assertThrows(IllegalArgumentException.class, () -> SqlStates.identifier("Upper"));
  }
}
