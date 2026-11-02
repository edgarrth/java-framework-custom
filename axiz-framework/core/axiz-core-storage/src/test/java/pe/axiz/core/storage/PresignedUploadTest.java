package pe.axiz.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class PresignedUploadTest {
  @Test
  void uploadInstructionsOwnTheirHeaderValuesAndRedactCredentialsFromDiagnostics() {
    var values = new ArrayList<>(List.of("bearer-secret"));
    var headers = new HashMap<String, List<String>>();
    headers.put("Authorization", values);
    URI uri = URI.create("https://example.invalid/upload?signature=private-value");
    var upload = new PresignedUpload(uri, headers);
    values.clear();
    headers.clear();
    assertEquals(uri, upload.url());
    assertEquals(List.of("bearer-secret"), upload.headers().get("Authorization"));
    assertThrows(UnsupportedOperationException.class, upload.headers()::clear);
    assertThrows(UnsupportedOperationException.class, upload.headers().get("Authorization")::clear);
    assertFalse(upload.toString().contains("private-value"));
    assertFalse(upload.toString().contains("bearer-secret"));
    assertTrue(upload.toString().contains("Authorization"));
  }

  @Test
  void existingSynchronousAndNativeImplementationsInheritCompleteContentTypeInstructions() {
    var sync = new InMemoryObjectStorage().presignUpload("x", "text/plain", Duration.ofMinutes(1));
    var async =
        new InMemoryAsyncObjectStorage()
            .presignUpload("x", "text/plain", Duration.ofMinutes(1))
            .toCompletableFuture()
            .join();
    assertEquals(sync, async);
    assertEquals(List.of("text/plain"), async.headers().get("Content-Type"));
    assertTrue(async.url().getQuery().contains("op=put"));
  }
}
