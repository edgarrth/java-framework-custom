package pe.axiz.quality.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.io.FileTemplateLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ejercita el motor real que usa WireMock frente a CVE-2026-55760. */
class TemplateTraversalTest {
  @TempDir Path directory;

  @Test
  void templateNamesCannotReadParentSiblingOrSymlinkTargets() throws Exception {
    Path templates = Files.createDirectory(directory.resolve("templates"));
    Path sibling = Files.createDirectory(directory.resolve("templates-sibling"));
    Files.writeString(templates.resolve("safe.hbs"), "Hello {{name}}");
    Files.writeString(directory.resolve("outside.hbs"), "synthetic-parent-secret");
    Path siblingSecret =
        Files.writeString(sibling.resolve("outside.hbs"), "synthetic-sibling-secret");
    Files.createSymbolicLink(templates.resolve("link.hbs"), siblingSecret);
    Handlebars engine = new Handlebars(new FileTemplateLoader(templates.toFile()));

    assertEquals("Hello Axiz", engine.compile("safe").apply(Map.of("name", "Axiz")));
    assertThrows(IllegalArgumentException.class, () -> engine.compile("../outside"));
    assertThrows(
        IllegalArgumentException.class, () -> engine.compile("../templates-sibling/outside"));
    assertThrows(IllegalArgumentException.class, () -> engine.compile("link"));
  }
}
