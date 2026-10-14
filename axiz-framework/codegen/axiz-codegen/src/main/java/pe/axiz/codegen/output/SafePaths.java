package pe.axiz.codegen.output;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

/**
 * Validation of generated relative paths and safe writes under a base directory: no absolute paths,
 * no {@code ..}, and no symbolic link followed below the base (a link planted in the destination,
 * such as {@code src -> /etc}, would otherwise redirect the write outside it). Without overwrite,
 * files are created atomically ({@code CREATE_NEW}). Threat model: the destination is a directory
 * of the user; another process able to swap its subdirectories concurrently is out of scope
 * (generate into a private directory in that case).
 */
public final class SafePaths {

  private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9._-]{1,120}$");

  private SafePaths() {}

  public static String relative(String path) {
    String p = path.replace('\\', '/');
    if (p.isEmpty() || p.startsWith("/") || p.length() > 400) {
      throw new IllegalArgumentException("Invalid generated path: " + path);
    }
    for (String s : p.split("/")) {
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
   * Resolves a generated path under {@code base}, refusing any symbolic link between the base and
   * the target (the target included). The base itself may be a link chosen by the user.
   *
   * @throws IOException when a component is a symbolic link
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
   * Writes a generated file without following symbolic links.
   *
   * @return {@code false} when the file exists and {@code overwrite} is off
   */
  public static boolean write(Path base, String relativePath, byte[] content, boolean overwrite)
      throws IOException {
    Path target = target(base, relativePath);
    if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    Files.createDirectories(target.getParent());
    target(base, relativePath); // directories created concurrently must not be links either
    try {
      Files.write(
          target,
          content,
          overwrite ? StandardOpenOption.CREATE : StandardOpenOption.CREATE_NEW,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS);
    } catch (FileAlreadyExistsException e) {
      return false; // created by someone else between the check and the write: never clobbered
    }
    return true;
  }
}
