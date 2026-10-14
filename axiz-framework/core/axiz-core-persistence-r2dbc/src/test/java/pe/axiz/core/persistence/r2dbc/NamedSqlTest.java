package pe.axiz.core.persistence.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class NamedSqlTest {

  @Test
  void namedParametersBecomePositionalAndRepeatedNamesShareTheirPosition() {
    NamedSql s = NamedSql.parse("UPDATE t SET a = :a, b = :b WHERE id = :id AND a <> :a");
    assertEquals("UPDATE t SET a = $1, b = $2 WHERE id = $3 AND a <> $1", s.sql());
    assertEquals(List.of("a", "b", "id"), s.names());
  }

  @Test
  void literalsIdentifiersCommentsAndCastsAreNotParameters() {
    NamedSql s =
        NamedSql.parse(
            "SELECT ':x', \"col:y\", n::text -- :z\n"
                + " FROM t /* :w */ WHERE v = :v AND s = 'it''s :q'");
    assertEquals(
        "SELECT ':x', \"col:y\", n::text -- :z\n FROM t /* :w */ WHERE v = $1 AND s = 'it''s :q'",
        s.sql());
    assertEquals(List.of("v"), s.names());
  }
}
