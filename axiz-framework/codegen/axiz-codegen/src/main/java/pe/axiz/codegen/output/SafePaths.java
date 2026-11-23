package pe.axiz.codegen.output;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

/**
 * Validación de las rutas relativas generadas y escrituras seguras bajo un directorio base: sin
 * rutas absolutas, sin {@code ..} y sin seguir enlaces simbólicos bajo la base (un enlace colocado
 * en el destino, como {@code src -> /etc}, redirigiría en otro caso la escritura fuera de él). Sin
 * overwrite, los archivos se crean de forma atómica ({@code CREATE_NEW}). Modelo de amenazas: el
 * destino es un directorio del usuario; otro proceso capaz de intercambiar sus subdirectorios de
 * forma concurrente queda fuera de alcance (en ese caso, se genera en un directorio privado).
 */
public final class SafePaths {

  private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9._-]{1,120}$");

  private SafePaths() {}

  public static String relative(String path) {
    String p = path.replace('\\', '/');
    if (p.isEmpty() || p.startsWith("/") || p.length() > 400) {
      throw new IllegalArgumentException("Invalid generated path: " + path);
    }
    for (String s : p.split("/", -1)) {
      segment(s);
    }
    return p;
  }

  public static String segment(String s) {
    if (!SEGMENT.matcher(s).matches() || s.equals(".") || s.equals("..")) {
      throw new IllegalArgumentException("Invalid path segment: " + s);
    }
    return s;
  }

  /**
   * El directorio que contiene un archivo. Una ruta relativa se resuelve primero contra el
   * directorio de trabajo, de modo que {@code pom.xml} también tiene padre.
   *
   * @param file una ruta de archivo
   * @return su directorio
   * @throws IllegalArgumentException para una raíz del sistema de archivos, que no tiene padre
   */
  public static Path parentOf(Path file) {
    Path parent = file.toAbsolutePath().getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Not a file path: " + file);
    }
    return parent;
  }

  /**
   * El último nombre de una ruta, o una cadena vacía para una raíz del sistema de archivos.
   *
   * @param path una ruta
   * @return su nombre de archivo
   */
  public static String fileName(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }

  /**
   * Resuelve una ruta generada bajo {@code base} y rechaza cualquier enlace simbólico entre la base
   * y el destino (incluido el destino). La base en sí puede ser un enlace elegido por el usuario.
   *
   * @throws IOException cuando un componente es un enlace simbólico
   */
  public static Path target(Path base, String relativePath) throws IOException {
    Path root = base.toAbsolutePath().normalize();
    Path target = root.resolve(relative(relativePath)).normalize();
    if (!target.startsWith(root) || target.equals(root)) {
      throw new IOException("Refusing to write outside " + root + ": " + relativePath);
    }
    Path current = root;
    for (Path part : root.relativize(target)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) {
        throw new IOException(
            "Refusing to follow the symbolic link " + root.relativize(current) + " in " + root);
      }
    }
    return target;
  }

  /**
   * Escribe un archivo generado sin seguir enlaces simbólicos.
   *
   * @return {@code false} cuando el archivo existe y {@code overwrite} está desactivado
   */
  public static boolean write(Path base, String relativePath, byte[] content, boolean overwrite)
      throws IOException {
    Path target = target(base, relativePath);
    if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    Files.createDirectories(parentOf(target));
    target(base, relativePath); // los directorios creados en paralelo tampoco deben ser enlaces
    try {
      Files.write(
          target,
          content,
          overwrite ? StandardOpenOption.CREATE : StandardOpenOption.CREATE_NEW,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS);
    } catch (FileAlreadyExistsException e) {
      return false; // otro lo creó entre la verificación y la escritura: nunca se pisa
    }
    return true;
  }
}
