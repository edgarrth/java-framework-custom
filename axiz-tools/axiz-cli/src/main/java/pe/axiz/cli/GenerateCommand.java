package pe.axiz.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import pe.axiz.codegen.project.ProjectLayout;
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
    ProjectLayout layout = ProjectLayout.read(dir, manifest);
    if (type.equals("http-client")) {
      if (contract == null) {
        throw new IllegalArgumentException("http-client needs --contract <openapi file>");
      }
      HttpClientGenerator.Result r =
          HttpClientGenerator.generate(
              manifest, name, Files.readString(contract, StandardCharsets.UTF_8));
      write(layout, r.files());
      // the first real client replaces the placeholder the http-client capability adds
      if (CapabilityAdder.mergeYaml(
          layout.target("src/main/resources/application.yaml"),
          r.properties(),
          List.of("axiz.http-client.clients.example"))) {
        ok("Updated src/main/resources/application.yaml");
      }
      r.notes().forEach(this::warn);
      formatSources(dir);
      return ExitCodes.OK;
    }
    String asyncPath = manifest.asyncapi();
    if (List.of("event", "handler", "event-handler").contains(type) && asyncPath != null) {
      if (!manifest.isService())
        throw new IllegalArgumentException("Event artifacts require a service");
      var document =
          pe.axiz.codegen.asyncapi.AsyncApiDocument.parse(
              Files.readString(SafePaths.target(dir, asyncPath)));
      String localName =
          name.startsWith(manifest.groupId() + ".")
              ? name.substring(manifest.groupId().length() + 1)
              : name;
      String requestedClass = pe.axiz.codegen.Names.pascal(localName);
      var payloads =
          pe.axiz.codegen.EventPayloads.generate(
              manifest.basePackage(), manifest.groupId(), manifest.structure(), document);
      Map<String, byte[]> selected = new java.util.LinkedHashMap<>();
      payloads.forEach(
          (path, bytes) -> {
            String generated = SafePaths.fileName(Path.of(path)).replace(".java", "");
            if (generated.equals(requestedClass)
                || generated.replaceFirst("V[1-9][0-9]*$", "").equals(requestedClass))
              selected.put(path, bytes);
          });
      if (selected.size() != 1)
        throw new IllegalArgumentException(
            "Select exactly one AsyncAPI event by its versioned name; matched " + selected.size());
      String generatedClass =
          SafePaths.fileName(Path.of(selected.keySet().iterator().next())).replace(".java", "");
      var adapters =
          pe.axiz.codegen.EventAdapters.generate(
              manifest.basePackage(),
              manifest.groupId(),
              manifest.structure(),
              manifest.stack(),
              document);
      boolean handlerRequested = !type.equals("event");
      if (handlerRequested
          && pe.axiz.codegen.model.Catalog.load()
              .resolve(
                  manifest.capabilities(),
                  pe.axiz.codegen.model.Catalog.store(manifest.persistence(), manifest.stack()))
              .stream()
              .noneMatch(
                  capability -> capability.starter().equals("axiz-messaging-spring-boot-starter")))
        throw new IllegalArgumentException(
            "Consuming AsyncAPI events requires a messaging capability: axiz add messaging-sqs");
      boolean handlerFound = false;
      for (var adapter : adapters.entrySet()) {
        String file = SafePaths.fileName(Path.of(adapter.getKey()));
        if (file.equals(generatedClass + "PayloadConfiguration.java"))
          selected.put(adapter.getKey(), adapter.getValue());
        if (handlerRequested && file.equals(generatedClass + "Handler.java")) {
          selected.put(adapter.getKey(), adapter.getValue());
          handlerFound = true;
        }
      }
      if (handlerRequested && !handlerFound)
        throw new IllegalArgumentException(
            "The AsyncAPI event is not declared as received by this service");
      write(layout, selected);
      if (handlerRequested) warn("Implement the event handler before consuming live messages");
      formatSources(dir);
      return ExitCodes.OK;
    }
    ArtifactGenerator generator = new ArtifactGenerator(environment.clock());
    ArtifactGenerator.Options options =
        new ArtifactGenerator.Options(category, title, source, target);
    String migration = Migrations.next(layout.module());
    ArtifactGenerator.Result r = generator.generate(manifest, type, name, options, null, migration);
    if (type.equals("error-code")) {
      // the errors class may exist already: generate again appending to it
      for (String path : r.created().keySet()) {
        Path existing = layout.target(path);
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
    write(layout, r.created());
    for (Map.Entry<String, byte[]> e : r.updated().entrySet()) {
      SafePaths.write(dir, layout.relative(e.getKey()), e.getValue(), true);
      ok("Updated " + e.getKey());
    }
    for (Map.Entry<String, String> e : r.appended().entrySet()) {
      Path file = layout.target(e.getKey());
      if (Files.exists(file)) {
        String current = Files.readString(file, StandardCharsets.UTF_8);
        String prefix = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
        SafePaths.write(
            dir,
            layout.relative(e.getKey()),
            (current + prefix + e.getValue()).getBytes(StandardCharsets.UTF_8),
            true);
        ok("Updated " + e.getKey());
      }
    }
    if (!r.dependencies().isEmpty()) {
      Path pom = layout.pom();
      String text = Files.readString(pom, StandardCharsets.UTF_8);
      String updated = text;
      for (String d : r.dependencies()) {
        String[] ga = d.split(":", -1);
        updated = PomEditor.addDependency(updated, ga[0], ga[1], null);
      }
      if (!updated.equals(text)) {
        SafePaths.write(
            dir,
            dir.toAbsolutePath().normalize().relativize(pom).toString(),
            updated.getBytes(StandardCharsets.UTF_8),
            true);
        ok("Updated pom.xml (" + String.join(", ", r.dependencies()) + ")");
      }
    }
    r.notes().forEach(this::warn);
    formatSources(dir);
    return ExitCodes.OK;
  }

  private void write(ProjectLayout layout, Map<String, byte[]> files) throws IOException {
    for (String path : files.keySet()) layout.target(path);
    for (Map.Entry<String, byte[]> e : files.entrySet()) {
      // validated path, no symbolic link followed inside the project
      if (!SafePaths.write(layout.root(), layout.relative(e.getKey()), e.getValue(), force)) {
        warn("Exists, not overwritten: " + e.getKey());
        continue;
      }
      ok("Created " + e.getKey());
    }
  }
}
