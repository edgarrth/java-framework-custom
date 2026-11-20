package pe.axiz.codegen.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import pe.axiz.codegen.ArtifactGenerator;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Extension;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.PomEditor;
import pe.axiz.codegen.project.ProjectLayout;
import pe.axiz.codegen.project.ProjectManifest;

/**
 * Generates sample projects so CI can build them as a team would: {@code defaults} creates one
 * project per archetype with its default style, stack and persistence; {@code all} creates every
 * valid combination. Writes {@code modules.json} (the project directories, for a CI matrix).
 *
 * <pre>
 * java -cp axiz-codegen.jar pe.axiz.codegen.tools.SampleProjects target/samples contract.yaml 2.0.0 defaults
 * </pre>
 */
public final class SampleProjects {

  private SampleProjects() {}

  /** Entry point: {@code <output> <openapi> <framework-version> [defaults|all|audit]}. */
  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      throw new IllegalArgumentException(
          "usage: SampleProjects <output> <openapi> <framework-version> [defaults|all|audit]");
    }
    String version = args[2];
    String mode = args.length > 3 ? args[3] : "defaults";
    if (!List.of("defaults", "all", "audit").contains(mode))
      throw new IllegalArgumentException("Unknown sample mode: " + mode);
    List<String> names =
        mode.equals("audit")
            ? auditSamples(
                Path.of(args[0]),
                Files.readString(Path.of(args[1]), StandardCharsets.UTF_8),
                version)
            : generate(
                Path.of(args[0]),
                Files.readString(Path.of(args[1]), StandardCharsets.UTF_8),
                "all".equals(mode),
                version,
                System.out);
    System.out.println("generated " + names.size() + " projects");
  }

  private static List<String> auditSamples(Path output, String contract, String version)
      throws IOException {
    var generator = ProjectGenerator.withDefaults();
    var names = new ArrayList<String>();
    for (Stack stack : List.of(Stack.SERVLET, Stack.REACTIVE)) {
      for (String route : List.of("outbox-sqs", "outbox-sns", "audit-sqs")) {
        String name = "audit-orders-" + stack.id() + "-" + route;
        List<String> capabilities =
            route.equals("audit-sqs")
                ? List.of("audit-sqs")
                : List.of("outbox", "messaging-" + route.substring("outbox-".length()));
        var request =
            ProjectRequest.builder()
                .name(name)
                .groupId("pe.axiz.samples")
                .basePackage("pe.axiz.samples.audit." + stack.id() + route.replace("-", ""))
                .description("Audit resource acceptance")
                .archetype("business")
                .structure(Structure.HEXAGONAL)
                .stack(stack)
                .persistence("aurora")
                .capabilities(capabilities)
                .openApi(contract)
                .asyncApi(null)
                .frameworkVersion(version)
                .extensions(List.of())
                .build();
        generator.generate(request).writeTo(output.resolve(name), true);
        persistenceArtifacts(output.resolve(name));
        names.add(name);
      }
    }
    Files.writeString(
        output.resolve("modules.json"),
        names.stream()
            .map(name -> "\"" + output.resolve(name).toString().replace("\\", "/") + "\"")
            .collect(Collectors.joining(",", "[", "]")),
        StandardCharsets.UTF_8);
    return names;
  }

  /**
   * Generates the samples into {@code output} and returns their directory names.
   *
   * @param all every valid combination instead of one project per archetype
   */
  public static List<String> generate(
      Path output, String openApi, boolean all, String frameworkVersion, PrintStream log)
      throws IOException {
    ProjectGenerator generator = ProjectGenerator.withDefaults();
    List<String> names = new ArrayList<>();
    for (Archetype archetype : generator.catalog().archetypes()) {
      for (ProjectRequest request : requests(archetype, openApi, all, frameworkVersion)) {
        var validation = generator.validate(request);
        if (!validation.valid()) {
          log.println("skip " + request.name() + ": " + validation.problems());
          continue;
        }
        generator.generate(request).writeTo(output.resolve(request.name()), true);
        if (!request.persistence().equals("none"))
          persistenceArtifacts(output.resolve(request.name()));
        names.add(request.name());
        log.println("ok   " + request.name());
      }
    }
    Files.createDirectories(output);
    Files.writeString(
        output.resolve("modules.json"),
        names.stream()
            .map(n -> "\"" + output.resolve(n).toString().replace("\\", "/") + "\"")
            .reduce((a, b) -> a + "," + b)
            .map(s -> "[" + s + "]")
            .orElse("[]"),
        StandardCharsets.UTF_8);
    return names;
  }

  /** Exercise persistence generation as well as the empty skeleton in the CI matrix. */
  private static void persistenceArtifacts(Path directory) throws IOException {
    var manifest = ProjectManifest.read(directory);
    var layout = ProjectLayout.read(directory, manifest);
    var artifacts = new ArtifactGenerator(Clock.systemUTC());
    for (String type : List.of("entity", "repository")) {
      var generated =
          artifacts.generate(
              manifest, type, "validation-record", ArtifactGenerator.Options.none(), null);
      for (var entry : generated.created().entrySet())
        SafePaths.write(directory, layout.relative(entry.getKey()), entry.getValue(), false);
      String pom = Files.readString(layout.pom(), StandardCharsets.UTF_8);
      for (String dependency : generated.dependencies()) {
        String[] coordinates = dependency.split(":", -1);
        pom = PomEditor.addDependency(pom, coordinates[0], coordinates[1], null);
      }
      Files.writeString(layout.pom(), pom, StandardCharsets.UTF_8);
    }
    for (var entry : artifacts.persistenceAcceptanceTest(manifest, "validation-record").entrySet())
      SafePaths.write(directory, layout.relative(entry.getKey()), entry.getValue(), false);
  }

  private static List<ProjectRequest> requests(
      Archetype archetype, String openApi, boolean all, String version) {
    List<ProjectRequest> out = new ArrayList<>();
    List<Structure> structures =
        all ? archetype.structures() : List.of(archetype.defaultStructure());
    List<Stack> stacks = all ? archetype.stacks() : List.of(archetype.defaultStack());
    List<String> persistence =
        all && !archetype.persistence().isEmpty()
            ? archetype.persistence()
            : List.of(archetype.defaultPersistence());
    for (Structure structure : structures) {
      for (Stack stack : stacks) {
        for (String store : persistence) {
          for (boolean cqrs :
              all && structure == Structure.HEXAGONAL && !archetype.extensions().isEmpty()
                  ? List.of(false, true)
                  : List.of(false)) {
            String name = name(archetype, structure, stack, store, all, cqrs);
            out.add(
                ProjectRequest.builder()
                    .name(name)
                    .groupId("pe.axiz.samples")
                    .basePackage("pe.axiz.samples." + name.replace("-", ""))
                    .description("Sample " + archetype.name() + " project generated by CI")
                    .archetype(archetype.name())
                    .structure(structure)
                    .stack(stack)
                    .persistence(store)
                    .capabilities(List.of())
                    .openApi(archetype.rest() ? openApi : null)
                    .asyncApi(null)
                    .frameworkVersion(version)
                    .extensions(cqrs ? List.of(Extension.CQRS) : List.of())
                    .build());
          }
        }
      }
    }
    return out;
  }

  private static String name(
      Archetype archetype,
      Structure structure,
      Stack stack,
      String store,
      boolean all,
      boolean cqrs) {
    if (!archetype.isService()) return "axiz-sample-" + archetype.name();
    String style =
        switch (structure) {
          case LAYERED -> "ly";
          case HEXAGONAL -> "hx";
          case EDA -> "ev";
          case BFF -> "bf";
          case BATCH -> "bt";
          case NONE -> "na";
        };
    // Technical layer names are reserved service nouns. Stable sample codes keep names valid
    // without relying on enum declaration order; the manifest retains the full architecture.
    String variant =
        all
            ? "-"
                + style
                + "-"
                + (stack == Stack.REACTIVE ? "rx" : "sv")
                + "-"
                + store
                + (cqrs ? "-q" : "")
            : "";
    return switch (archetype.name()) {
      case "business" -> "sample-orders" + variant;
      case "event-processor" -> "sample-notifications" + variant + "-processor";
      case "batch" -> "sample-settlement" + variant + "-batch";
      default -> "sample" + variant + "-" + archetype.name();
    };
  }
}
