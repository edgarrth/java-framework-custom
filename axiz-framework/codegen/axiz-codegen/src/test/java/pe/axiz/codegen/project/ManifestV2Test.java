package pe.axiz.codegen.project;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestV2Test {
  @Test
  void refusesUnpublishedDraftSchemasWithoutConvertingThem(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve(".axiz"));
    for (String schema : new String[] {"1", "99", "2.5"}) {
      Files.writeString(dir.resolve(ProjectManifest.PATH), "schema: " + schema + "\n");
      assertThrows(IOException.class, () -> ProjectManifest.read(dir));
    }
  }
}
