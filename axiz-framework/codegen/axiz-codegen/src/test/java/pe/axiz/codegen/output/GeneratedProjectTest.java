package pe.axiz.codegen.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedProjectTest {

  private static byte[] b(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void rejectsPathsOutsideTheProject() {
    for (String bad :
        new String[] {"../x", "/etc/passwd", "a/../../b", "a\\..\\b", "", "a//b", "a/./b"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new GeneratedProject("p", Map.of(bad, b("x"))),
          bad);
    }
    assertThrows(
        IllegalArgumentException.class, () -> new GeneratedProject("..", Map.of("a", b("x"))));
  }

  @Test
  void writesWithoutOverwritingByDefault(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("keep.txt"), "mine");
    GeneratedProject p =
        new GeneratedProject(
            "p",
            Map.of("keep.txt", b("theirs"), "dir/new.txt", b("new"), "run.sh", b("#!/bin/sh")));
    assertEquals(2, p.writeTo(tmp, false));
    assertEquals("mine", Files.readString(tmp.resolve("keep.txt")));
    assertEquals("new", Files.readString(tmp.resolve("dir/new.txt")));
    assertTrue(Files.isExecutable(tmp.resolve("run.sh")));
    assertEquals(3, p.writeTo(tmp, true));
    assertEquals("theirs", Files.readString(tmp.resolve("keep.txt")));
  }

  @Test
  void contentIsCopiedDefensively() {
    byte[] content = b("a");
    GeneratedProject p = new GeneratedProject("p", Map.of("f", content));
    content[0] = 'z';
    assertEquals("a", p.text("f"));
    assertFalse(p.files().isEmpty());
    // regression: the returned map and arrays are copies
    p.files().get("f")[0] = 'z';
    assertEquals("a", p.text("f"));
    p.files().put("g", b("x"));
    assertEquals(java.util.Set.of("f"), p.paths());
    assertEquals(1, p.totalBytes());
  }

  /** Regression: a symbolic link inside the destination redirected writes outside it. */
  @Test
  void neverFollowsSymbolicLinksInTheDestination(@TempDir Path tmp) throws Exception {
    Path outside = Files.createDirectories(tmp.resolve("outside"));
    Path project = Files.createDirectories(tmp.resolve("project"));
    Files.createSymbolicLink(project.resolve("src"), outside);
    GeneratedProject p = new GeneratedProject("p", Map.of("src/evil.txt", b("x")));
    java.io.IOException e = assertThrows(java.io.IOException.class, () -> p.writeTo(project, true));
    assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
    assertFalse(Files.exists(outside.resolve("evil.txt")));

    Path victim = Files.writeString(tmp.resolve("victim.txt"), "original");
    Files.createSymbolicLink(project.resolve("README.md"), victim);
    GeneratedProject q = new GeneratedProject("p", Map.of("README.md", b("overwritten")));
    assertThrows(java.io.IOException.class, () -> q.writeTo(project, true));
    assertEquals("original", Files.readString(victim));
  }

  @Test
  void zipsAreReproducible() throws Exception {
    GeneratedProject p =
        new GeneratedProject("p", Map.of("b.txt", b("2"), "a/a.txt", b("1"), "mvnw", b("#!")));
    java.io.ByteArrayOutputStream first = new java.io.ByteArrayOutputStream();
    p.writeZip(first);
    java.io.ByteArrayOutputStream second = new java.io.ByteArrayOutputStream();
    p.writeZip(second);
    org.junit.jupiter.api.Assertions.assertArrayEquals(first.toByteArray(), second.toByteArray());
    try (java.util.zip.ZipInputStream in =
        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(first.toByteArray()))) {
      java.util.List<String> names = new java.util.ArrayList<>();
      for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
        names.add(e.getName());
        assertEquals(GeneratedProject.ZIP_TIME, e.getTimeLocal());
      }
      assertEquals(java.util.List.of("p/a/a.txt", "p/b.txt", "p/mvnw"), names);
    }
  }
}
