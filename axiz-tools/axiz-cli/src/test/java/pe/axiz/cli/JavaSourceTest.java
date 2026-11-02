package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JavaSourceTest {
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
