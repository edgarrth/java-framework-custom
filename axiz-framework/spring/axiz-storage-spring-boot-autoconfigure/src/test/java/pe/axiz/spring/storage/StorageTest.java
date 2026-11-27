package pe.axiz.spring.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.storage.InMemoryObjectStorage;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;

class StorageTest {
  @Test
  void missingProviderFailsInsteadOfSilentlyUsingMemory() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageAutoConfiguration.class))
        .withPropertyValues("axiz.storage.type=s3", "axiz.config.environment=prod")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "axiz.storage.type=",
        "axiz.storage.max-presign-ttl=0s",
        "axiz.storage.max-presign-ttl=-1s"
      })
  void invalidStoragePolicyPreventsStartup(String invalid) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageAutoConfiguration.class))
        .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
        .withPropertyValues(invalid)
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void explicitProviderRetainsIdentityAndSignedUrlPolicy() {
    var storage = new InMemoryObjectStorage();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageAutoConfiguration.class))
        .withBean(ObjectStorage.class, () -> storage)
        .withPropertyValues("axiz.storage.type=custom", "axiz.storage.max-presign-ttl=30s")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(ObjectStorage.class);
              assertThat(ctx.getBean(ObjectStorage.class)).isSameAs(storage);
              assertThat(ctx.getBean(AxizStorageProperties.class).maxPresignTtl())
                  .isEqualTo(Duration.ofSeconds(30));
            });
  }

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
