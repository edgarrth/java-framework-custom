package pe.axiz.adapter.secrets.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;

/**
 * Resolves secrets mounted as files (Kubernetes Secret volume): {@code <dir>/<name>}. Names are
 * restricted to safe characters so they can never escape the directory.
 */
public final class FileSecretResolver implements SecretResolver {

  private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

  private final Path directory;

  public FileSecretResolver(Path directory) {
    this.directory = directory.toAbsolutePath().normalize();
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    String fileName = fileName(name);
    Path file = directory.resolve(fileName).normalize();
    if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return SecretFileContents.read(directory, file).value();
    } catch (NoSuchFileException removedDuringRotation) {
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read secret file " + fileName, e);
    }
  }

  static String fileName(String name) {
    String fileName = name.replace('/', '.').replace('#', '.');
    if (!SAFE_NAME.matcher(fileName).matches() || fileName.contains("..")) {
      throw new IllegalArgumentException("Invalid secret name");
    }
    return fileName;
  }
}
