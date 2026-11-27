package pe.axiz.spring.secrets.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

class ConfigTreeTest {
  @Test
  void theConfigTreeImportIsMergedOnceAndCanBeDisabled(@TempDir Path dir) {
    SecretsConfigTreeEnvironmentPostProcessor epp = new SecretsConfigTreeEnvironmentPostProcessor();
    assertTrue(epp.getOrder() < Ordered.HIGHEST_PRECEDENCE + 10);
    String entry = "optional:configtree:" + dir + "/";

    MockEnvironment existing =
        new MockEnvironment()
            .withProperty("axiz.secrets.directory", dir.toString())
            .withProperty("spring.config.import", "optional:file:/etc/axiz/extra.yaml");
    epp.postProcessEnvironment(existing, new SpringApplication());
    assertEquals(
        entry + ",optional:file:/etc/axiz/extra.yaml",
        existing.getProperty("spring.config.import"));
    epp.postProcessEnvironment(existing, new SpringApplication());
    assertEquals(
        entry + ",optional:file:/etc/axiz/extra.yaml",
        existing.getProperty("spring.config.import"));

    MockEnvironment off =
        new MockEnvironment()
            .withProperty("axiz.secrets.directory", dir + "/")
            .withProperty("axiz.secrets.config-tree", "false");
    epp.postProcessEnvironment(off, new SpringApplication());
    assertNull(off.getProperty("spring.config.import"));
  }
}
