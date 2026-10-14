package pe.axiz.codegen.output;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generated files kept in memory until written. Paths are validated so nothing can be written
 * outside the target directory, whatever the request contained.
 */
public final class GeneratedProject {

  /** Fixed entry time of generated archives (reproducible output). */
  static final LocalDateTime ZIP_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

  private final String rootName;
  private final Map<String, byte[]> files;

  public GeneratedProject(String rootName, Map<String, byte[]> files) {
    this.rootName = SafePaths.segment(rootName);
    Map<String, byte[]> sorted = new TreeMap<>();
    files.forEach((path, content) -> sorted.put(SafePaths.relative(path), content.clone()));
    this.files = Collections.unmodifiableMap(sorted);
  }

  public String rootName() {
    return rootName;
  }

  /** Copy of the generated files (path to content); changing it does not alter this project. */
  public Map<String, byte[]> files() {
    Map<String, byte[]> copy = new TreeMap<>();
    files.forEach((path, content) -> copy.put(path, content.clone()));
    return copy;
  }

  /** Generated paths, sorted. */
  public java.util.Set<String> paths() {
    return files.keySet();
  }

  public String text(String path) {
    byte[] b = files.get(path);
    if (b == null) {
      throw new IllegalArgumentException("Not generated: " + path);
    }
    return new String(b, java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Size in bytes of every generated file. */
  public long totalBytes() {
    return files.values().stream().mapToLong(b -> b.length).sum();
  }

  /**
   * Writes a ZIP whose entries live under {@code <rootName>/}. The archive is reproducible: entries
   * are sorted and carry a fixed timestamp, so the same project always produces the same bytes.
   */
  public void writeZip(OutputStream out) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> e : files.entrySet()) {
        ZipEntry entry = new ZipEntry(rootName + "/" + e.getKey());
        entry.setTimeLocal(ZIP_TIME);
        zip.putNextEntry(entry);
        zip.write(e.getValue());
        zip.closeEntry();
      }
    }
  }

  /**
   * Writes the files under {@code directory}; existing files are kept unless {@code overwrite}.
   * Symbolic links below the directory are never followed (see {@link SafePaths#write}).
   *
   * @return number of files written
   */
  public int writeTo(Path directory, boolean overwrite) throws IOException {
    int written = 0;
    for (Map.Entry<String, byte[]> e : files.entrySet()) {
      if (!SafePaths.write(directory, e.getKey(), e.getValue(), overwrite)) {
        continue;
      }
      if (e.getKey().equals("mvnw") || e.getKey().endsWith(".sh")) {
        SafePaths.target(directory, e.getKey()).toFile().setExecutable(true, false);
      }
      written++;
    }
    return written;
  }
}
