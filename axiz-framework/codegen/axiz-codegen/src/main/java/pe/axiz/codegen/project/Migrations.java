package pe.axiz.codegen.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import pe.axiz.codegen.output.SafePaths;

/**
 * Flyway versions of new migrations: the next integer after the highest {@code V<n>__} file, so
 * generated migrations continue the project's own numbering ({@code V1__init}, {@code V2__...}).
 */
public final class Migrations {

  /** Folder of the Flyway migrations of a service. */
  public static final String DIR = "src/main/resources/db/migration";

  private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

  private Migrations() {}

  /** Next version for the project at {@code projectDir} ({@code 1} when there is none). */
  public static String next(Path projectDir) {
    Path dir = projectDir.resolve(DIR);
    BigInteger max = BigInteger.ZERO;
    if (Files.isDirectory(dir)) {
      try (Stream<Path> files = Files.list(dir)) {
        for (Path f : files.toList()) {
          Matcher m = VERSIONED.matcher(SafePaths.fileName(f));
          if (m.matches()) {
            max = max.max(new BigInteger(m.group(1)));
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return max.add(BigInteger.ONE).toString();
  }
}
