package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaSourceTest {
  static Stream<String> opaqueFragments() {
    return Stream.of(
        "//",
        "// text",
        "/**/",
        "/* text */",
        "/* unterminated",
        "\"text\"",
        "'x'",
        "'\\\''",
        "\"escaped \\\" quote\"",
        "\"\\",
        "\"\"\"\ntext\r\n\"\"\"",
        "\"\"\"\n\\\"\"\" remains literal\n\"\"\"");
  }

  @ParameterizedTest
  @MethodSource("opaqueFragments")
  void maskingPreservesEverySourceOffsetAndLineEnding(String fragment) {
    String expected = fragment.replaceAll("[^\\r\\n]", " ");
    assertEquals(expected, JavaSource.code(fragment));
  }

  @Test
  void operatorsAndCodeAfterLiteralsCannotDisappearFromDiagnostics() {
    for (String source : java.util.List.of("/", "/x", "x / y", "a*=2;", "a++;", "\r\n", ""))
      assertEquals(source, JavaSource.code(source));
    assertEquals("a      b", JavaSource.code("a/*x*/ b"));
    assertEquals("     \nThread.sleep(1);", JavaSource.code("// hi\nThread.sleep(1);"));
    assertEquals("   ; System.out.print(1);", JavaSource.code("\"x\"; System.out.print(1);"));
    assertEquals("         ;danger();", JavaSource.code("\"\"\"\"\"\"   ;danger();"));
  }

  @Test
  void keepsAnnotationsAndMasksCommentsCharacterStringsAndTextBlocks() {
    String source =
        "@ExtendWith(BlockHoundExtension.class)\n"
            + "// fake @ExtendWith(BlockHoundExtension.class)\n"
            + "/* block @ExtendWith(BlockHoundExtension.class) */\n"
            + "String example = \"@ExtendWith(BlockHoundExtension.class)\";\n"
            + "char quote = '\\\'';\n"
            + "String documentation = \"\"\"\n@ExtendWith(BlockHoundExtension.class)\n\"\"\";\n"
            + "String escaped = \"\\\"@ExtendWith(BlockHoundExtension.class)\";\n";
    String code = JavaSource.code(source);
    assertEquals(source.length(), code.length());
    assertEquals(
        source.chars().filter(c -> c == '\n').count(), code.chars().filter(c -> c == '\n').count());
    assertTrue(code.startsWith("@ExtendWith(BlockHoundExtension.class)\n"));
    assertEquals(1, code.split("BlockHoundExtension", -1).length - 1);
    assertFalse(code.contains("fake"));
    assertEquals("   ", JavaSource.code("/*x"));
    assertEquals("  ", JavaSource.code("\"\\"));
  }
}
