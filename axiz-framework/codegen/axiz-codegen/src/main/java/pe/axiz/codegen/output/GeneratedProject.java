package pe.axiz.codegen.output;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Archivos generados que se mantienen en memoria hasta su escritura. Las rutas se validan para que
 * nada pueda escribirse fuera del directorio de destino, sin importar lo que contenga la solicitud.
 */
public final class GeneratedProject {

  /** Momento fijo de las entradas de los archivos comprimidos generados (salida reproducible). */
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

  /** Copia de los archivos generados (ruta a contenido); modificarla no altera este proyecto. */
  public Map<String, byte[]> files() {
    Map<String, byte[]> copy = new TreeMap<>();
    files.forEach((path, content) -> copy.put(path, content.clone()));
    return copy;
  }

  /** Rutas generadas, ordenadas. */
  public Set<String> paths() {
    return files.keySet();
  }

  public String text(String path) {
    byte[] b = files.get(path);
    if (b == null) {
      throw new IllegalArgumentException("Not generated: " + path);
    }
    return new String(b, StandardCharsets.UTF_8);
  }

  /** Tamaño en bytes de cada archivo generado. */
  public long totalBytes() {
    return files.values().stream().mapToLong(b -> b.length).sum();
  }

  /**
   * Escribe un ZIP cuyas entradas residen bajo {@code <rootName>/}. El archivo es reproducible: las
   * entradas se ordenan y llevan una marca de tiempo fija, por lo que el mismo proyecto siempre
   * produce los mismos bytes.
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
   * Escribe los archivos bajo {@code directory}; los archivos existentes se conservan salvo con
   * {@code overwrite}. Los enlaces simbólicos bajo el directorio nunca se siguen (ver {@link
   * SafePaths#write}).
   *
   * @return cantidad de archivos escritos
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
