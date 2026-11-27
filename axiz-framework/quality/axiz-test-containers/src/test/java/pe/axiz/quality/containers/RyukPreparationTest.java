package pe.axiz.quality.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

class RyukPreparationTest {
  @Test
  void incompatibleReaperConfigurationFailsBeforeContactingDocker() {
    assertThrows(
        IllegalStateException.class,
        () -> TestContainerImages.requirePatchedReaper("custom.IncompatibleSubstitutor"));
    TestContainerImages.requirePatchedReaper(PatchedRyukImageSubstitutor.class.getName());
  }

  @Test
  void existingImageIsReusedWithoutBuildingAndClientIsClosed() throws Exception {
    var docker = mock(DockerClient.class);
    var inspect = mock(InspectImageCmd.class);
    String expected = TestContainerImages.imageName("ryuk", TestContainerImages.files("ryuk"));
    when(docker.inspectImageCmd(expected)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(new InspectImageResponse());
    var image =
        new PatchedRyukImageSubstitutor(() -> docker)
            .apply(DockerImageName.parse("testcontainers/ryuk:0.14.0"));
    assertEquals(expected, image.asCanonicalNameString());
    assertTrue(image.isCompatibleWith(DockerImageName.parse("testcontainers/ryuk")));
    verify(docker, never()).buildImageCmd(any(File.class));
    verify(docker).close();
  }

  @Test
  void missingImageBuildsTheCompleteVerifiedContextAndDeletesTemporaryFilesOnBothOutcomes()
      throws Exception {
    for (boolean fail : new boolean[] {false, true}) {
      var docker = mock(DockerClient.class);
      var inspect = mock(InspectImageCmd.class);
      var build = mock(BuildImageCmd.class);
      var callback = mock(BuildImageResultCallback.class);
      var directory = new AtomicReference<Path>();
      var files = TestContainerImages.files("ryuk");
      String expected = TestContainerImages.imageName("ryuk", files);
      when(docker.inspectImageCmd(expected)).thenReturn(inspect);
      when(inspect.exec()).thenThrow(new NotFoundException("absent"));
      when(docker.buildImageCmd(any(File.class)))
          .thenAnswer(
              call -> {
                Path path = ((File) call.getArgument(0)).toPath();
                directory.set(path);
                for (var file : files.entrySet())
                  assertEquals(file.getValue(), Files.readString(path.resolve(file.getKey())));
                return build;
              });
      when(build.withTags(Set.of(expected))).thenReturn(build);
      when(build.exec(any(BuildImageResultCallback.class))).thenReturn(callback);
      if (fail)
        when(callback.awaitImageId(10, TimeUnit.MINUTES))
            .thenThrow(new IllegalStateException("build failed"));
      else when(callback.awaitImageId(10, TimeUnit.MINUTES)).thenReturn("sha256:fixture");
      var substitutor = new PatchedRyukImageSubstitutor(() -> docker);
      var original = DockerImageName.parse("testcontainers/ryuk:0.14.0");
      if (fail) assertThrows(IllegalStateException.class, () -> substitutor.apply(original));
      else assertEquals(expected, substitutor.apply(original).asCanonicalNameString());
      assertFalse(Files.exists(directory.get()));
      verify(callback).awaitImageId(10, TimeUnit.MINUTES);
      verify(docker).close();
    }
  }
}
