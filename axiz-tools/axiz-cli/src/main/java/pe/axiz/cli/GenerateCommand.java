package pe.axiz.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.ArtifactGenerator;
import pe.axiz.codegen.HttpClientGenerator;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.CapabilityAdder;
import pe.axiz.codegen.project.Migrations;
import pe.axiz.codegen.project.PomEditor;
import pe.axiz.codegen.project.ProjectManifest;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code axiz generate <type> <name>}: code that follows the style of the project. */
@Command(
    mixinStandardHelpOptions = true,
    name = "generate",
    aliases = "g",
    description =
        "Generates usecase, command, query, handler, event, event-handler, entity, repository,"
            + " controller, dto, mapper, http-client, migration, error-code or test.",
    footer = {
      "",
      "Examples:",
      "  axiz generate usecase approve-payment",
      "  axiz generate error-code limit-exceeded --category unprocessable --title \"Daily limit"
          + " exceeded\"",
      "  axiz generate http-client core-banking --contract core-banking.yaml",
      "  axiz generate mapper payment --source CreatePaymentRequest --target"
          + " pe.axiz.payments.domain.Payment"
    })
class GenerateCommand extends BaseCommand implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "<type>", description = "Artifact type.")
  String type = "";

  @Parameters(index = "1", paramLabel = "<name>", description = "Name, e.g. approve-payment.")
  String name = "";

  @Option(names = "--category", description = "error-code: error category (default UNPROCESSABLE).")
  @Nullable String category;

  @Option(names = "--title", description = "error-code: title.")
  @Nullable String title;

  @Option(names = "--source", description = "mapper: source type.")
  @Nullable String source;

  @Option(names = "--target", description = "mapper: target type.")
  @Nullable String target;

  @Option(names = "--contract", description = "http-client: OpenAPI contract of the API to call.")
  @Nullable Path contract;

  @Option(names = "--force", description = "Overwrite existing files.")
  boolean force;

  @Override
  public Integer call() throws IOException {
    Path dir = project();
    ProjectManifest manifest = ProjectManifest.read(dir);
    if (type.equals("http-client")) {
      if (contract == null) {
        throw new IllegalArgumentException("http-client needs --contract <openapi file>");
      }
      HttpClientGenerator.Result r =
          HttpClientGenerator.generate(
              manifest, name, Files.readString(contract, StandardCharsets.UTF_8));
      write(dir, r.files());
      // the first real client replaces the placeholder the http-client capability adds
      if (CapabilityAdder.mergeYaml(
          dir.resolve("src/main/resources/application.yaml"),
          r.properties(),
          List.of("axiz.http-client.clients.example"))) {
        ok("Updated src/main/resources/application.yaml");
      }
      r.notes().forEach(this::warn);
      return ExitCodes.OK;
    }
    ArtifactGenerator generator = new ArtifactGenerator(environment.clock());
    ArtifactGenerator.Options options =
        new ArtifactGenerator.Options(category, title, source, target);
    String migration = Migrations.next(dir);
    ArtifactGenerator.Result r = generator.generate(manifest, type, name, options, null, migration);
    if (type.equals("error-code")) {
      // the errors class may exist already: generate again appending to it
      for (String path : r.created().keySet()) {
        Path existing = dir.resolve(path);
        if (Files.exists(existing)) {
          r =
              generator.generate(
                  manifest,
                  type,
                  name,
                  options,
                  Files.readString(existing, StandardCharsets.UTF_8));
        }
      }
    }
    write(dir, r.created());
    for (Map.Entry<String, byte[]> e : r.updated().entrySet()) {
      SafePaths.write(dir, e.getKey(), e.getValue(), true);
      ok("Updated " + e.getKey());
    }
    for (Map.Entry<String, String> e : r.appended().entrySet()) {
      Path file = SafePaths.target(dir, e.getKey());
      if (Files.exists(file)) {
        String current = Files.readString(file, StandardCharsets.UTF_8);
        String prefix = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
        Files.writeString(
            file, prefix + e.getValue(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ok("Updated " + e.getKey());
      }
    }
    if (!r.dependencies().isEmpty()) {
      Path pom = dir.resolve("pom.xml");
      String text = Files.readString(pom, StandardCharsets.UTF_8);
      String updated = text;
      for (String d : r.dependencies()) {
        String[] ga = d.split(":");
        updated = PomEditor.addDependency(updated, ga[0], ga[1], null);
      }
      if (!updated.equals(text)) {
        Files.writeString(pom, updated, StandardCharsets.UTF_8);
        ok("Updated pom.xml (" + String.join(", ", r.dependencies()) + ")");
      }
    }
    r.notes().forEach(this::warn);
    return ExitCodes.OK;
  }

  private void write(Path dir, Map<String, byte[]> files) throws IOException {
    for (Map.Entry<String, byte[]> e : files.entrySet()) {
      // validated path, no symbolic link followed inside the project
      if (!SafePaths.write(dir, e.getKey(), e.getValue(), force)) {
        warn("Exists, not overwritten: " + e.getKey());
        continue;
      }
      ok("Created " + e.getKey());
    }
  }
}
