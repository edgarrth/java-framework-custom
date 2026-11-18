package pe.axiz.spring.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;

class StorageTest {
  @Test
  void memoryStorageForTests() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageAutoConfiguration.class))
        .withPropertyValues("axiz.storage.type=memory", "axiz.config.environment=local")
        .run(
            ctx -> {
              ObjectStorage storage = ctx.getBean(ObjectStorage.class);
              storage.put(
                  "reports/a.csv",
                  new ByteArrayInputStream("x,y".getBytes()),
                  ObjectMetadata.of("text/csv", 3));
              assertEquals(3, storage.head("reports/a.csv").orElseThrow().contentLength());
              assertTrue(storage.list("reports/", 10).contains("reports/a.csv"));
            });
  }

  @Test
  void memoryStorageIsRefusedWhereSeveralReplicasRun() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageAutoConfiguration.class))
        .withPropertyValues("axiz.storage.type=memory", "axiz.config.environment=prod")
        .run(ctx -> assertThat(ctx).hasFailed());
  }
}
