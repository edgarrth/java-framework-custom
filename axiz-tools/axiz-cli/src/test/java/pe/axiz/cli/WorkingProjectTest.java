package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkingProjectTest {
  @Test
  void appliesMovesAndDeletionsAfterReviewWithoutCopyingBuildCachesOrGit(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("old.txt"), "before\n");
    Files.writeString(root.resolve("removed.txt"), "removed\n");
    Files.createDirectories(root.resolve(".git"));
    Files.writeString(root.resolve(".git/index"), "git index stays unchanged");
    Files.createDirectories(root.resolve("target"));
    Files.writeString(root.resolve("target/keep.txt"), "existing build artifact");
    Path temporary;
    try (WorkingProject project = WorkingProject.create(root)) {
      temporary = project.directory();
      assertFalse(Files.exists(temporary.resolve("target")));
      assertFalse(Files.exists(temporary.resolve(".git")));
      Files.createDirectories(temporary.resolve("nested"));
      Files.move(temporary.resolve("old.txt"), temporary.resolve("nested/new.txt"));
      Files.delete(temporary.resolve("removed.txt"));
      Files.writeString(temporary.resolve("nested/new.txt"), "after\n");
      Files.writeString(temporary.resolve(".flattened-pom.xml"), "generated build metadata");
      project.review();
      assertEquals("before\n", Files.readString(root.resolve("old.txt")));
      String patch = Files.readString(root.resolve("target/rewrite/rewrite.patch"));
      assertTrue(patch.contains("+++ b/nested/new.txt"));
      assertTrue(patch.contains("+after"));
      assertTrue(patch.contains("new file mode"));
      assertTrue(patch.contains("deleted file mode"));
      assertFalse(patch.contains(".flattened-pom.xml"));
      project.apply();
      assertFalse(Files.exists(root.resolve("old.txt")));
      assertFalse(Files.exists(root.resolve("removed.txt")));
      assertEquals("after\n", Files.readString(root.resolve("nested/new.txt")));
      assertFalse(Files.exists(root.resolve(".flattened-pom.xml")));
    }
    assertFalse(Files.exists(temporary));
    assertEquals("git index stays unchanged", Files.readString(root.resolve(".git/index")));
    assertEquals("existing build artifact", Files.readString(root.resolve("target/keep.txt")));
  }

  @Test
  void preservesTheOriginalOnFailureOrDryRunAndRejectsConcurrentEdits(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("pom.xml"), "original");
    try (WorkingProject project = WorkingProject.create(root)) {
      Files.writeString(project.directory().resolve("pom.xml"), "partially rewritten");
      project.review();
    }
    assertEquals("original", Files.readString(root.resolve("pom.xml")));
    try (WorkingProject project = WorkingProject.create(root)) {
      Files.writeString(project.directory().resolve("pom.xml"), "rewritten");
      Files.writeString(root.resolve("pom.xml"), "concurrent edit");
      assertThrows(IOException.class, project::apply);
    }
    assertEquals("concurrent edit", Files.readString(root.resolve("pom.xml")));
  }

  @Test
  void binaryAndMissingTrailingNewlineChangesAreRetainedForReview(@TempDir Path root)
      throws Exception {
    Files.write(root.resolve("asset.bin"), new byte[] {0, 1});
    Files.writeString(root.resolve("plain.txt"), "without newline");
    try (WorkingProject project = WorkingProject.create(root)) {
      Files.write(project.directory().resolve("asset.bin"), new byte[] {0, 2});
      Files.writeString(project.directory().resolve("plain.txt"), "changed");
      project.review();
      String patch = Files.readString(root.resolve("target/rewrite/rewrite.patch"));
      assertTrue(patch.contains("Binary files differ: asset.bin"));
      assertTrue(patch.contains("\\ No newline at end of file"));
      project.apply();
      assertEquals(2, Files.readAllBytes(root.resolve("asset.bin"))[1]);
    }
  }

  @Test
  void preservesValidSourceNamesWithSpacesAndQuotesThemInTheReview(@TempDir Path root)
      throws Exception {
    Files.writeString(root.resolve("Architecture Notes.md"), "original\n");
    try (WorkingProject project = WorkingProject.create(root)) {
      Files.writeString(project.directory().resolve("Architecture Notes.md"), "updated\n");
      project.review();
      assertTrue(
          Files.readString(root.resolve("target/rewrite/rewrite.patch"))
              .contains("+++ \"b/Architecture Notes.md\""));
      project.apply();
    }
    assertEquals("updated\n", Files.readString(root.resolve("Architecture Notes.md")));
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void restoresEarlierFilesWhenApplyingALaterChangeFails(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("a.txt"), "original first");
    Path locked = Files.createDirectory(root.resolve("b"));
    Files.writeString(locked.resolve("last.txt"), "original last");
    Path temporary;
    try (WorkingProject project = WorkingProject.create(root)) {
      temporary = project.directory();
      Files.writeString(temporary.resolve("a.txt"), "rewritten first");
      Files.writeString(temporary.resolve("b/last.txt"), "rewritten last");
      var previous = Files.getPosixFilePermissions(locked);
      try {
        Files.setPosixFilePermissions(
            locked, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        org.junit.jupiter.api.Assumptions.assumeFalse(
            Files.isWritable(locked), "Cannot test permission denial as a privileged user");
        assertThrows(IOException.class, project::apply);
        assertEquals("original first", Files.readString(root.resolve("a.txt")));
        assertEquals("original last", Files.readString(locked.resolve("last.txt")));
      } finally {
        Files.setPosixFilePermissions(locked, previous);
      }
    }
    assertFalse(Files.exists(temporary));
  }

  @Test
  void reviewPatchIsAcceptedByGitForAddedDeletedAndChangedFiles(@TempDir Path root)
      throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(new ProcessRunner.Local().available("git"));
    Files.writeString(root.resolve("existing.txt"), "before\n");
    Files.writeString(root.resolve("deleted.txt"), "deleted\n");
    try (WorkingProject project = WorkingProject.create(root)) {
      Files.writeString(project.directory().resolve("existing.txt"), "after\n");
      Files.delete(project.directory().resolve("deleted.txt"));
      Files.writeString(project.directory().resolve("added.txt"), "added\n");
      project.review();
      Process process =
          new ProcessBuilder(
                  "git",
                  "apply",
                  "--check",
                  root.resolve("target/rewrite/rewrite.patch").toString())
              .directory(root.toFile())
              .redirectErrorStream(true)
              .start();
      String output =
          new String(
              process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(0, process.waitFor(), output);
    }
  }

  @Test
  void neverFollowsAnExternalSymlinkInTheSourceOrRecipeOutput(@TempDir Path root) throws Exception {
    Path source = Files.createDirectory(root.resolve("source"));
    Path external = root.resolve("external.txt");
    Files.writeString(external, "external");
    Path link = source.resolve("link.txt");
    Files.createSymbolicLink(link, external);
    assertThrows(IOException.class, () -> WorkingProject.create(source));
    Files.delete(link);
    Files.writeString(source.resolve("local.txt"), "local");
    try (WorkingProject project = WorkingProject.create(source)) {
      Files.createSymbolicLink(project.directory().resolve("link.txt"), external);
      assertThrows(IOException.class, project::apply);
    }
    assertEquals("external", Files.readString(external));
    assertEquals("local", Files.readString(source.resolve("local.txt")));
  }
}
