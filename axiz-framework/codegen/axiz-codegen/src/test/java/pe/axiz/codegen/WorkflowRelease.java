package pe.axiz.codegen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.codegen.render.TemplateSet;

/** Shared test/fixture semantics; never changes effective workflow pins. */
public final class WorkflowRelease {
  private WorkflowRelease() {}

  public static String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Workflow fixtures require strict UTF-8", exception);
    }
  }

  public static Map<String, Object> document() {
    try (var input =
        WorkflowRelease.class.getResourceAsStream(
            "/workflow-contracts/release-normalization.yaml")) {
      return new Yaml().load(decode(Objects.requireNonNull(input).readAllBytes()));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, String> specification() {
    try (var input =
        WorkflowRelease.class.getResourceAsStream("/workflow-contracts/workflow-release-v2.json")) {
      Map<String, Object> contract =
          new Yaml().load(decode(Objects.requireNonNull(input).readAllBytes()));
      var normalization = (Map<String, String>) contract.get("normalization");
      return Map.of(
          "token",
          normalization.get("token"),
          "pattern",
          normalization.get("prefix") + contract.get("pattern") + normalization.get("suffix"));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static Set<String> releases(String value) {
    var versions = new LinkedHashSet<String>();
    var matcher = Pattern.compile(specification().get("pattern")).matcher(value);
    while (matcher.find()) versions.add(matcher.group());
    return versions;
  }

  public static String normalize(String value) {
    var spec = specification();
    if (value.contains(spec.get("token")))
      throw new IllegalArgumentException("Normalization token already present");
    if (releases(value).size() != 1)
      throw new IllegalArgumentException("Expected one consistent canonical workflow release");
    return Pattern.compile(spec.get("pattern")).matcher(value).replaceAll(spec.get("token"));
  }

  public static String expected() {
    var versions = new LinkedHashSet<String>();
    for (var set : List.of("service", "library", "starter", "artifacts")) {
      for (var entry : TemplateSet.load(set).entries()) {
        if (!entry.path().startsWith(".github/workflows/")) continue;
        String path =
            entry.template().startsWith("/")
                ? "/axiz/templates" + entry.template()
                : "/axiz/templates/" + set + "/" + entry.template();
        try (var input = TemplateSet.class.getResourceAsStream(path)) {
          versions.addAll(releases(decode(Objects.requireNonNull(input).readAllBytes())));
        } catch (IOException exception) {
          throw new UncheckedIOException(exception);
        }
      }
    }
    if (versions.size() != 1)
      throw new IllegalArgumentException("Packaged workflow templates must adopt one release");
    return versions.iterator().next();
  }

  public static void assertProject(GeneratedProject project) {
    String expected = expected();
    boolean found = false;
    for (var path : project.files().keySet()) {
      if (!path.startsWith(".github/workflows/")) continue;
      var versions = releases(project.text(path));
      if (versions.isEmpty()) continue;
      found = true;
      if (!versions.equals(Set.of(expected)))
        throw new AssertionError(
            path + ": generated workflow versions differ from packaged templates");
    }
    if (!found) throw new AssertionError("Generated project has no workflow release pins");
  }
}
