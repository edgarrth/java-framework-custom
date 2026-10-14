package pe.axiz.cli;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts a generated project ZIP safely: no entry may escape the target, with size limits. */
final class ZipExtractor {

  static final long MAX_TOTAL = 50L * 1024 * 1024;
  static final int MAX_ENTRIES = 5_000;

  private ZipExtractor() {}

  /**
   * Extracts {@code zip} into {@code target}, dropping the single root folder of the archive.
   *
   * @return number of files written
   */
  static int extract(byte[] zip, Path target, boolean overwrite) throws IOException {
    Path base = target.toAbsolutePath().normalize();
    int files = 0;
    long total = 0;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        if (++files > MAX_ENTRIES) {
          throw new IOException("Archive has too many entries");
        }
        String name = e.getName().replace('\\', '/');
        int slash = name.indexOf('/');
        String relative = slash < 0 ? name : name.substring(slash + 1);
        if (relative.isEmpty() || e.isDirectory()) {
          continue;
        }
        Path out = base.resolve(relative).normalize();
        if (!out.startsWith(base) || relative.startsWith("/")) {
          throw new IOException("Refusing entry outside the target: " + name);
        }
        if (Files.exists(out) && !overwrite) {
          continue;
        }
        Files.createDirectories(out.getParent());
        total += copy(in, out, MAX_TOTAL - total);
      }
    }
    return files;
  }

  private static long copy(InputStream in, Path out, long budget) throws IOException {
    byte[] buffer = new byte[8192];
    long written = 0;
    try (var os = Files.newOutputStream(out)) {
      for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
        written += n;
        if (written > budget) {
          throw new IOException("Archive exceeds " + MAX_TOTAL + " bytes");
        }
        os.write(buffer, 0, n);
      }
    }
    return written;
  }
}
