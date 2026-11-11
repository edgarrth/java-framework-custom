package pe.axiz.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StorageTest {

  @Test
  void putGetListDelete() throws Exception {
    InMemoryObjectStorage s = new InMemoryObjectStorage();
    s.put(
        "settlement/2026/01/31/file.txt",
        new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
        ObjectMetadata.of("text/plain", 5));
    try (StoredObject o = s.get("settlement/2026/01/31/file.txt").orElseThrow()) {
      assertEquals("hello", new String(o.content().readAllBytes(), StandardCharsets.UTF_8));
      assertEquals(5, o.metadata().contentLength());
      assertNotNull(o.metadata().checksumSha256());
    }
    assertEquals(List.of("settlement/2026/01/31/file.txt"), s.list("settlement/", 10));
    assertTrue(s.head("settlement/2026/01/31/file.txt").isPresent());
    assertNotNull(s.presignGet("settlement/2026/01/31/file.txt", Duration.ofMinutes(5)));
    assertNotNull(s.presignUpload("x.txt", "text/plain", Duration.ofMinutes(5)));
    s.delete("settlement/2026/01/31/file.txt");
    assertTrue(s.get("settlement/2026/01/31/file.txt").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"../etc/passwd", "/abs", "a//b", "a/../b", "a/./b", "bad key", "line\nbreak", ""})
  void rejectsUnsafeKeys(String key) {
    assertThrows(IllegalArgumentException.class, () -> ObjectKey.validate(key));
  }
}
