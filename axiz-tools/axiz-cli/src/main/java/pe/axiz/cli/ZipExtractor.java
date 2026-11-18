package pe.axiz.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import pe.axiz.codegen.output.SafePaths;

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
    if (Files.isSymbolicLink(base))
      throw new IOException("Archive target must not be a symbolic link");
    int entries = 0;
    long total = 0;
    Map<String, byte[]> contents = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        if (++entries > MAX_ENTRIES) {
          throw new IOException("Archive has too many entries");
        }
        String name = e.getName().replace('\\', '/');
        try {
          SafePaths.relative(e.isDirectory() ? name.substring(0, name.length() - 1) : name);
        } catch (IllegalArgumentException invalid) {
          throw new IOException("Invalid archive entry path", invalid);
        }
        int slash = name.indexOf('/');
        String relative = slash < 0 ? name : name.substring(slash + 1);
        if (relative.isEmpty() || e.isDirectory()) {
          // getNextEntry drains unread data; bound that data too, including directory entries.
          if (in.read() != -1) throw new IOException("Directory entry contains data");
          continue;
        }
        SafePaths.target(base, relative);
        if (contents.containsKey(relative)) throw new IOException("Duplicate archive destination");
        byte[] content = read(in, MAX_TOTAL - total);
        total += content.length;
        contents.put(relative, content);
      }
    }
    if (contents.isEmpty()) throw new IOException("Archive contains no project files");
    for (String relative : contents.keySet()) {
      Path file = base.resolve(relative);
      if (Files.exists(file) && !Files.isRegularFile(file))
        throw new IOException("Archive file destination is not a regular file");
      for (Path parent = file.getParent();
          parent != null && parent.startsWith(base);
          parent = parent.getParent()) {
        if (contents.containsKey(base.relativize(parent).toString().replace('\\', '/'))
            || (Files.exists(parent) && !Files.isDirectory(parent)))
          throw new IOException("Archive directory conflicts with a file");
      }
    }
    // Validate the entire archive before touching existing project files. SafePaths checks links
    // again when writing; concurrent hostile replacement of the user's directory is out of scope.
    int written = 0;
    for (var file : contents.entrySet())
      if (SafePaths.write(base, file.getKey(), file.getValue(), overwrite)) written++;
    return written;
  }

  private static byte[] read(InputStream in, long budget) throws IOException {
    byte[] buffer = new byte[8192];
    long written = 0;
    try (var os = new ByteArrayOutputStream()) {
      for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
        written += n;
        if (written > budget) {
          throw new IOException("Archive exceeds " + MAX_TOTAL + " bytes");
        }
        os.write(buffer, 0, n);
      }
      return os.toByteArray();
    }
  }
}
