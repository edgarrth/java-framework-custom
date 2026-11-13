package pe.axiz.codegen.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Extension;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

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
            new ProjectRequest(
                name,
                "pe.axiz.samples",
                "pe.axiz.samples.audit." + stack.id() + route.replace("-", ""),
                "Audit resource acceptance",
                "business",
                Structure.HEXAGONAL,
                stack,
                "aurora",
                capabilities,
                contract,
                null,
                version);
        generator.generate(request).writeTo(output.resolve(name), true);
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
                new ProjectRequest(
                    name,
                    "pe.axiz.samples",
                    "pe.axiz.samples." + name.replace("-", ""),
                    "Sample " + archetype.name() + " project generated by CI",
                    archetype.name(),
                    structure,
                    stack,
                    store,
                    List.of(),
                    archetype.rest() ? openApi : null,
                    null,
                    version,
                    cqrs ? List.of(Extension.CQRS) : List.of()));
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
