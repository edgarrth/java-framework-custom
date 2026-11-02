package pe.axiz.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import pe.axiz.codegen.output.SafePaths;

/**
 * Isolates recipe writes, detects concurrent edits and retains a baseline for failed application.
 */
final class WorkingProject implements AutoCloseable {
  private static final Set<String> EXCLUDED =
      Set.of(
          ".git",
          "target",
          "node_modules",
          ".idea",
          ".gradle",
          ".m2",
          ".flattened-pom.xml",
          "dependency-reduced-pom.xml",
          "pom.xml.versionsBackup");
  private final Path source;
  private final Path temporary;
  private final Path baseline;
  private final Path working;
  private final Map<String, State> initial;
  private boolean preserve;

  private record State(
      String sha256,
      boolean executable,
      Set<java.nio.file.attribute.PosixFilePermission> permissions) {}

  private WorkingProject(Path source, Path temporary, Map<String, State> initial) {
    this.source = source;
    this.temporary = temporary;
    this.baseline = temporary.resolve("baseline");
    this.working = temporary.resolve("project");
    this.initial = initial;
  }

  static WorkingProject create(Path directory) throws IOException {
    Path source = directory.toAbsolutePath().normalize();
    Path temporary = Files.createTempDirectory("axiz-upgrade-");
    try {
      copy(source, temporary.resolve("baseline"));
      Map<String, State> initial = snapshot(temporary.resolve("baseline"));
      if (!initial.equals(snapshot(source)))
        throw new IOException("Project changed while preparing migration");
      copy(temporary.resolve("baseline"), temporary.resolve("project"));
      return new WorkingProject(source, temporary, initial);
    } catch (IOException | RuntimeException error) {
      try {
        remove(temporary);
      } catch (IOException cleanup) {
        error.addSuppressed(cleanup);
      }
      throw error;
    }
  }

  Path directory() {
    return working;
  }

  void review() throws IOException {
    Map<String, State> updated = snapshot(working);
    StringBuilder patch = new StringBuilder();
    for (String name : changed(updated)) {
      byte[] before =
          initial.containsKey(name) ? Files.readAllBytes(file(baseline, name)) : new byte[0];
      byte[] after =
          updated.containsKey(name) ? Files.readAllBytes(file(working, name)) : new byte[0];
      patch
          .append("diff --git ")
          .append(patchPath("a/" + name))
          .append(" ")
          .append(patchPath("b/" + name))
          .append('\n');
      if (!initial.containsKey(name)) {
        patch
            .append("new file mode ")
            .append(mode(java.util.Objects.requireNonNull(updated.get(name))))
            .append('\n');
      } else if (!updated.containsKey(name)) {
        patch.append("deleted file mode ").append(mode(initial.get(name))).append('\n');
      } else if (initial.get(name).executable() != updated.get(name).executable()) {
        patch.append("old mode ").append(mode(initial.get(name))).append('\n');
        patch.append("new mode ").append(mode(updated.get(name))).append('\n');
      }
      if (binary(before) || binary(after)) {
        patch.append("Binary files differ: ").append(name).append('\n');
        continue;
      }
      patch
          .append("--- ")
          .append(initial.containsKey(name) ? patchPath("a/" + name) : "/dev/null")
          .append('\n');
      patch
          .append("+++ ")
          .append(updated.containsKey(name) ? patchPath("b/" + name) : "/dev/null")
          .append('\n');
      String oldText = new String(before, StandardCharsets.UTF_8);
      String newText = new String(after, StandardCharsets.UTF_8);
      long oldLines = oldText.lines().count();
      long newLines = newText.lines().count();
      patch
          .append("@@ -")
          .append(oldLines == 0 ? 0 : 1)
          .append(',')
          .append(oldLines)
          .append(" +")
          .append(newLines == 0 ? 0 : 1)
          .append(',')
          .append(newLines)
          .append(" @@\n");
      lines(patch, oldText, '-');
      lines(patch, newText, '+');
    }
    SafePaths.write(
        working,
        "target/rewrite/rewrite.patch",
        patch.toString().getBytes(StandardCharsets.UTF_8),
        true);
    Path report = file(source, "target/rewrite");
    copy(file(working, "target/rewrite"), report);
  }

  void apply() throws IOException {
    if (!initial.equals(snapshot(source)))
      throw new IOException("Project changed during migration; review the patch and retry");
    Map<String, State> updated = snapshot(working);
    Set<String> changed = changed(updated);
    for (String name : changed) file(source, name);
    try {
      for (String name : changed) restore(working, source, name, updated.containsKey(name));
    } catch (IOException failure) {
      for (String name : changed) {
        try {
          Path current = file(source, name);
          if (initial.containsKey(name)
              && Files.isRegularFile(current)
              && initial.get(name).equals(state(current))) continue;
          restore(baseline, source, name, initial.containsKey(name));
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
          preserve = true;
        }
      }
      if (preserve)
        throw new IOException(
            "Migration could not be restored completely; recover the baseline at " + baseline,
            failure);
      throw failure;
    }
  }

  private Set<String> changed(Map<String, State> updated) {
    Set<String> names = new TreeSet<>(initial.keySet());
    names.addAll(updated.keySet());
    names.removeIf(name -> java.util.Objects.equals(initial.get(name), updated.get(name)));
    return names;
  }

  private static String mode(State state) {
    return state.executable() ? "100755" : "100644";
  }

  private static Path file(Path root, String name) throws IOException {
    Path relative = Path.of(name);
    Path base = root.toAbsolutePath().normalize();
    Path target = base.resolve(relative).normalize();
    if (relative.isAbsolute()
        || !relative.normalize().equals(relative)
        || !target.startsWith(base)
        || target.equals(base)
        || name.chars().anyMatch(value -> value < 32)) {
      throw new IOException("Unsafe migration path");
    }
    Path current = base;
    if (Files.isSymbolicLink(current)) throw new IOException("Migration root cannot be a symlink");
    for (Path part : base.relativize(target)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current))
        throw new IOException("Migration path cannot contain a symlink");
    }
    return target;
  }

  private static String patchPath(String name) {
    String value = java.io.File.separatorChar == '\\' ? name.replace('\\', '/') : name;
    if (value.matches("[A-Za-z0-9._/-]+")) return value;
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static void restore(Path from, Path to, String name, boolean present) throws IOException {
    Path target = file(to, name);
    if (!present) {
      Files.deleteIfExists(target);
      return;
    }
    Files.createDirectories(SafePaths.parentOf(target));
    file(to, name);
    Files.copy(
        file(from, name),
        target,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES);
  }

  private static void copy(Path from, Path to) throws IOException {
    visit(
        from,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
              throws IOException {
            if (!directory.equals(from) && EXCLUDED.contains(SafePaths.fileName(directory)))
              return FileVisitResult.SKIP_SUBTREE;
            Files.createDirectories(
                directory.equals(from) ? to : file(to, from.relativize(directory).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            if (EXCLUDED.contains(SafePaths.fileName(file))) return FileVisitResult.CONTINUE;
            if (!attributes.isRegularFile() || attributes.isSymbolicLink())
              throw new IOException("Unsupported migration source file: " + from.relativize(file));
            restore(from, to, from.relativize(file).toString(), true);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static Map<String, State> snapshot(Path root) throws IOException {
    Map<String, State> result = new TreeMap<>();
    visit(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            return !directory.equals(root) && EXCLUDED.contains(SafePaths.fileName(directory))
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            if (EXCLUDED.contains(SafePaths.fileName(file))) return FileVisitResult.CONTINUE;
            if (!attributes.isRegularFile() || attributes.isSymbolicLink())
              throw new IOException("Unsupported migration source file: " + root.relativize(file));
            result.put(root.relativize(file).toString(), state(file));
            return FileVisitResult.CONTINUE;
          }
        });
    return Map.copyOf(result);
  }

  private static State state(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return new State(
        HexFormat.of().formatHex(digest.digest()),
        Files.isExecutable(file),
        file.getFileSystem().supportedFileAttributeViews().contains("posix")
            ? Set.copyOf(Files.getPosixFilePermissions(file))
            : Set.of());
  }

  private static boolean binary(byte[] bytes) {
    for (byte value : bytes) if (value == 0) return true;
    try {
      StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
      return false;
    } catch (java.nio.charset.CharacterCodingException error) {
      return true;
    }
  }

  private static void lines(StringBuilder patch, String text, char prefix) {
    text.lines().forEach(line -> patch.append(prefix).append(line).append('\n'));
    if (!text.isEmpty() && !text.endsWith("\n")) patch.append("\\ No newline at end of file\n");
  }

  private static void visit(Path root, SimpleFileVisitor<Path> visitor) throws IOException {
    if (Files.isSymbolicLink(root)) throw new IOException("Migration root cannot be a symlink");
    Files.walkFileTree(root, visitor);
  }

  private static void remove(Path root) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) throw error;
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  @Override
  public void close() throws IOException {
    if (!preserve) remove(temporary);
  }
}
