package pe.axiz.spring.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;

class SecretsTest {

  @Test
  void mountedFilesWinAndValuesAreNeverPrinted(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("db-password"), "s3cr3t\n");
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecretsAutoConfiguration.class))
        .withPropertyValues("axiz.secrets.directory=" + dir, "axiz.secrets.environment=false")
        .withBean(
            SecretSource.class,
            () -> new SecretSource("test", name -> Optional.of(SecretValue.of("from-source"))))
        .run(
            ctx -> {
              SecretResolver r = ctx.getBean(SecretResolver.class);
              SecretValue v = r.resolve("db-password").orElseThrow();
              assertEquals("s3cr3t", v.reveal());
              assertFalse(v.toString().contains("s3cr3t"));
              assertEquals("from-source", r.resolve("other").orElseThrow().reveal());
            });
  }

  @Test
  void configTreeImportedOnlyWhenMounted(@TempDir Path dir) {
    MockEnvironment env =
        new MockEnvironment().withProperty("axiz.secrets.directory", dir.toString());
    new SecretsConfigTreeEnvironmentPostProcessor()
        .postProcessEnvironment(env, new SpringApplication());
    assertTrue(env.getProperty("spring.config.import").startsWith("optional:configtree:" + dir));
    MockEnvironment none =
        new MockEnvironment()
            .withProperty("axiz.secrets.directory", dir.resolve("missing").toString());
    new SecretsConfigTreeEnvironmentPostProcessor()
        .postProcessEnvironment(none, new SpringApplication());
    assertEquals(null, none.getProperty("spring.config.import"));
  }
}
