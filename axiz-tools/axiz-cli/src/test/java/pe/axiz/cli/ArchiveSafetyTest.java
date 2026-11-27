package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveSafetyTest {
  @Test
  void symbolicDirectoryAndFileTargetsCannotEscapeEvenWithForce(@TempDir Path root)
      throws Exception {
    Path outside = Files.createDirectories(root.resolve("outside"));
    Files.writeString(outside.resolve("kept.txt"), "keep");
    Path target = Files.createDirectories(root.resolve("project"));
    Files.createSymbolicLink(target.resolve("src"), outside);
    Files.createSymbolicLink(target.resolve("linked.txt"), outside.resolve("kept.txt"));
    Path linkedRoot = Files.createSymbolicLink(root.resolve("linked-project"), outside);
    assertThrows(
        IOException.class,
        () ->
            ZipExtractor.extract(
                OnlineModeTest.zip(Map.of("svc/kept.txt", "overwrite")), linkedRoot, true));
    for (boolean force : new boolean[] {false, true}) {
      for (String entry : new String[] {"svc/src/kept.txt", "svc/linked.txt"})
        assertThrows(
            IOException.class,
            () ->
                ZipExtractor.extract(
                    OnlineModeTest.zip(Map.of(entry, "overwrite")), target, force));
    }
    assertEquals("keep", Files.readString(outside.resolve("kept.txt")));
  }

  @Test
  void maliciousLateEntriesNeverLeavePartiallyReplacedProjects(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("pom.xml"), "keep");
    for (String bad :
        new String[] {
          "svc/../escape",
          "svc/../../escape",
          "svc//absolute",
          "/absolute",
          "svc/C:/drive",
          "svc/dir/../../../escape"
        }) {
      Map<String, String> entries = new LinkedHashMap<>();
      entries.put("svc/pom.xml", "replace");
      entries.put(bad, "bad");
      assertThrows(
          IOException.class, () -> ZipExtractor.extract(OnlineModeTest.zip(entries), root, true));
      assertEquals("keep", Files.readString(root.resolve("pom.xml")));
    }
    assertThrows(IOException.class, () -> ZipExtractor.extract(new byte[] {1, 2, 3}, root, true));
    assertThrows(
        IOException.class,
        () -> ZipExtractor.extract(OnlineModeTest.zip(Map.of("svc/", "")), root, true));
  }

  @Test
  void normalizedDuplicateDestinationsAreRejected(@TempDir Path root) throws Exception {
    var entries = new LinkedHashMap<String, String>();
    entries.put("svc/pom.xml", "first");
    entries.put("another/pom.xml", "second");
    assertThrows(
        IOException.class, () -> ZipExtractor.extract(OnlineModeTest.zip(entries), root, true));
    assertFalse(Files.exists(root.resolve("pom.xml")));
  }

  @Test
  void fileDirectoryConflictsAndDirectoryPayloadsAreRejectedBeforeWriting(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("pom.xml"), "keep");
    for (boolean reverse : new boolean[] {false, true}) {
      var entries = new LinkedHashMap<String, String>();
      entries.put("svc/pom.xml", "replace");
      entries.put(reverse ? "svc/src/Main.java" : "svc/src", "first");
      entries.put(reverse ? "svc/src" : "svc/src/Main.java", "second");
      assertThrows(
          IOException.class, () -> ZipExtractor.extract(OnlineModeTest.zip(entries), root, true));
      assertEquals("keep", Files.readString(root.resolve("pom.xml")));
    }
    var entries = new LinkedHashMap<String, String>();
    entries.put("svc/pom.xml", "replace");
    entries.put("svc/src/", "unexpected payload");
    assertThrows(
        IOException.class, () -> ZipExtractor.extract(OnlineModeTest.zip(entries), root, true));
    assertEquals("keep", Files.readString(root.resolve("pom.xml")));
    Files.createDirectory(root.resolve("already-directory"));
    entries.remove("svc/src/");
    entries.put("svc/already-directory", "file");
    assertThrows(
        IOException.class, () -> ZipExtractor.extract(OnlineModeTest.zip(entries), root, true));
    assertEquals("keep", Files.readString(root.resolve("pom.xml")));
  }

  @Test
  void exactEntryAndByteLimitsAreAcceptedButTheirSuccessorsFailBeforeWriting(@TempDir Path root)
      throws Exception {
    byte[] block = new byte[8192];
    var archive = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(archive)) {
      zip.putNextEntry(new ZipEntry("svc/large.bin"));
      for (long written = 0; written < ZipExtractor.MAX_TOTAL; written += block.length)
        zip.write(block);
      zip.closeEntry();
    }
    assertEquals(1, ZipExtractor.extract(archive.toByteArray(), root, false));
    assertEquals(ZipExtractor.MAX_TOTAL, Files.size(root.resolve("large.bin")));
    Files.writeString(root.resolve("large.bin"), "keep");
    archive.reset();
    try (var zip = new ZipOutputStream(archive)) {
      zip.putNextEntry(new ZipEntry("svc/large.bin"));
      for (long written = 0; written < ZipExtractor.MAX_TOTAL; written += block.length)
        zip.write(block);
      zip.write(1);
      zip.closeEntry();
    }
    assertThrows(IOException.class, () -> ZipExtractor.extract(archive.toByteArray(), root, true));
    assertEquals("keep", Files.readString(root.resolve("large.bin")));
    var entries = new LinkedHashMap<String, String>();
    for (int i = 0; i < ZipExtractor.MAX_ENTRIES; i++) entries.put("svc/file-" + i, "");
    assertEquals(
        ZipExtractor.MAX_ENTRIES, ZipExtractor.extract(OnlineModeTest.zip(entries), root, false));
    entries.put("svc/one-too-many", "");
    assertThrows(
        IOException.class, () -> ZipExtractor.extract(OnlineModeTest.zip(entries), root, true));
    assertFalse(Files.exists(root.resolve("one-too-many")));
    assertTrue(Files.isRegularFile(root.resolve("file-0")));
  }
}
