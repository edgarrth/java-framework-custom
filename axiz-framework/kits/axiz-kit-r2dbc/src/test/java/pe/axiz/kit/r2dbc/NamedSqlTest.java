package pe.axiz.kit.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class NamedSqlTest {

  @Test
  void truncatedCommentsQuotesAndSingleOperatorsCannotCreatePhantomBindings() {
    for (String sql :
        List.of(
            "",
            ":",
            "-",
            "/",
            "'",
            "\"",
            "-- :name",
            "/* :name",
            "':hidden",
            "\":hidden",
            "-x",
            "/x",
            ":1",
            "::",
            "''''",
            "\"\"\"\"")) {
      var parsed = NamedSql.parse(sql);
      assertEquals(sql, parsed.sql());
      assertEquals(List.of(), parsed.names());
    }
    assertEquals("$1", NamedSql.parse(":a").sql());
    assertEquals(List.of("a"), NamedSql.parse(":a").names());
    assertThrows(UnsupportedOperationException.class, () -> NamedSql.parse(":a").names().clear());
  }

  @Test
  void postgresDollarQuotesAndNestedCommentsNeverExposeLiteralParameters() {
    for (String literal :
        List.of(
            "$$:hidden$$",
            "$tag$:hidden ':x' $other$ $tag$",
            "$_tag_1$:hidden$_tag_1$",
            "$é$:hidden$é$",
            "/* outer /* inner */ :hidden */")) {
      var parsed = NamedSql.parse("SELECT " + literal + ", :actual");
      assertEquals("SELECT " + literal + ", $1", parsed.sql());
      assertEquals(List.of("actual"), parsed.names());
    }
    for (String unfinished : List.of("$$:hidden", "$tag$:hidden", "/* outer /* inner */ :hidden")) {
      assertEquals(unfinished, NamedSql.parse(unfinished).sql());
      assertEquals(List.of(), NamedSql.parse(unfinished).names());
    }
    for (String other : List.of("$", "$1", "$bad-tag$", "$tag", "column$tag$"))
      assertEquals(other + " + $1", NamedSql.parse(other + " + :value").sql());
  }

  @Test
  void escapeStringsAndDoubledDelimitersKeepTheirColonTextUntouched() {
    String escaped = "E'it\\'s :hidden'";
    String sql = "SELECT " + escaped + ", :value";
    assertEquals("SELECT " + escaped + ", $1", NamedSql.parse(sql).sql());
    assertEquals(List.of("value"), NamedSql.parse(sql).names());
    assertEquals("E'\\", NamedSql.parse("E'\\").sql());
    assertEquals("e'\\\\' + $1", NamedSql.parse("e'\\\\' + :v").sql());
    assertEquals("'\\' + $1", NamedSql.parse("'\\' + :v").sql());
    assertEquals("nameE'\\' + $1", NamedSql.parse("nameE'\\' + :v").sql());
    for (String between : List.of("\n", "\r\n", " -- note\n", " /* nested /* x */\n */ ")) {
      String continued = "E'first'" + between + "'it\\'s :hidden'";
      assertEquals(continued + ", $1", NamedSql.parse(continued + ", :value").sql());
      assertEquals(List.of("value"), NamedSql.parse(continued + ", :value").names());
    }
    for (String tail :
        List.of(
            "",
            " -- no newline :hidden",
            " /* no newline */",
            "\n",
            " /* unterminated",
            " /* no newline */ + :value")) {
      String input = "E'first'" + tail;
      assertEquals(input.replace(":value", "$1"), NamedSql.parse(input).sql());
    }
  }

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
