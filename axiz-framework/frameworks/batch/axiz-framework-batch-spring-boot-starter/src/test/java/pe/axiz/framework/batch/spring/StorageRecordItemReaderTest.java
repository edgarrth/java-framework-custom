package pe.axiz.framework.batch.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import pe.axiz.core.storage.InMemoryObjectStorage;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.framework.batch.CsvRecordReader;

class StorageRecordItemReaderTest {

  @Test
  void restartsAfterTheLastCommittedItem() throws Exception {
    InMemoryObjectStorage storage = new InMemoryObjectStorage();
    byte[] csv = "id\n1\n2\n3\n".getBytes(StandardCharsets.UTF_8);
    storage.put(
        "in/file.csv", new ByteArrayInputStream(csv), ObjectMetadata.of("text/csv", csv.length));
    StorageRecordItemReader<String> first = reader(storage);
    ExecutionContext ctx = new ExecutionContext();
    first.open(ctx);
    assertEquals("1", first.read());
    first.update(ctx);
    first.close();

    StorageRecordItemReader<String> restarted = reader(storage);
    restarted.open(ctx);
    assertEquals("2", restarted.read());
    assertEquals("3", restarted.read());
    assertNull(restarted.read());
    restarted.close();
  }

  private static StorageRecordItemReader<String> reader(InMemoryObjectStorage storage) {
    return new StorageRecordItemReader<>(
        "movements",
        storage,
        "in/file.csv",
        in -> new CsvRecordReader<>(in, StandardCharsets.UTF_8, ',', true, row -> row.get(0)));
  }
}
