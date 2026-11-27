package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

/**
 * Certificados de vida corta por prueba; localhost es de forma deliberada el único nombre de
 * servidor válido.
 */
record TlsMaterial(Path certificate, Path key, Path untrusted) {
  static TlsMaterial create(Path directory) throws Exception {
    Path certificate = directory.resolve("server.crt");
    Path key = directory.resolve("server.key");
    Path untrusted = directory.resolve("untrusted.crt");
    certificate(directory, certificate, key, "localhost");
    certificate(directory, untrusted, directory.resolve("untrusted.key"), "untrusted");
    return new TlsMaterial(certificate, key, untrusted);
  }

  private static void certificate(Path directory, Path certificate, Path key, String name)
      throws Exception {
    var arguments =
        new ArrayList<>(
            List.of(
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-days",
                "2",
                "-subj",
                "/CN=" + name,
                "-addext",
                "subjectAltName=DNS:" + name,
                "-keyout",
                key.toString(),
                "-out",
                certificate.toString()));
    Path log = directory.resolve(name + "-openssl.log");
    Process process =
        new ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "certificate generation must finish");
      assertEquals(0, process.exitValue(), () -> "Cannot generate TLS fixture; see " + log);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  <C extends GenericContainer<?>> C copyTo(C container) throws Exception {
    // Clave efímera solo para el fixture, legible por el usuario sin privilegios de cada imagen.
    // Nunca se persiste.
    container
        .withCopyToContainer(
            Transferable.of(Files.readAllBytes(certificate), 0644), "/tmp/axiz-server.crt")
        .withCopyToContainer(Transferable.of(Files.readAllBytes(key), 0644), "/tmp/axiz-server.key")
        .withStartupTimeout(Duration.ofSeconds(90));
    return container;
  }
}
