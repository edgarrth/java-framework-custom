package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.openapi.Json;

class SupportTest {

  private static byte[] zip(String... entries) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(out)) {
      for (String e : entries) {
        z.putNextEntry(new ZipEntry(e));
        z.write("x".getBytes());
        z.closeEntry();
      }
    }
    return out.toByteArray();
  }

  @Test
  void extractsDroppingTheRootFolder(@TempDir Path tmp) throws Exception {
    ZipExtractor.extract(zip("svc/pom.xml", "svc/src/A.java"), tmp, false);
    assertTrue(Files.exists(tmp.resolve("pom.xml")));
    assertTrue(Files.exists(tmp.resolve("src/A.java")));
  }

  @Test
  void rejectsZipSlip(@TempDir Path tmp) throws Exception {
    assertThrows(
        IOException.class,
        () -> ZipExtractor.extract(zip("svc/../../evil.sh"), tmp.resolve("x"), false));
  }

  @Test
  void writesJson() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", "a\"b\n");
    m.put("list", List.of(1, true));
    m.put("none", null);
    assertEquals("{\"name\":\"a\\\"b\\n\",\"list\":[1,true],\"none\":null}", Json.write(m));
  }

  @Test
  void requiresHttpsForRemoteServers() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new InitializrClient(URI.create("http://initializr.example"), null));
    new InitializrClient(URI.create("http://localhost:8080"), null);
    new InitializrClient(URI.create("https://initializr.example"), "t");
  }
}
