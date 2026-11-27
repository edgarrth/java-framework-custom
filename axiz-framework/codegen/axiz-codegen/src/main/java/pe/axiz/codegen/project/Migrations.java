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
 * Versiones de Flyway de las migraciones nuevas: el siguiente entero después del archivo {@code
 * V<n>__} más alto, de modo que las migraciones generadas continúan la numeración propia del
 * proyecto ({@code V1__init}, {@code V2__...}).
 */
public final class Migrations {

  /** Carpeta de las migraciones de Flyway de un servicio. */
  public static final String DIR = "src/main/resources/db/migration";

  private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

  private Migrations() {}

  /** Siguiente versión para el proyecto de {@code projectDir} ({@code 1} cuando no hay ninguna). */
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
